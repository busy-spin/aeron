/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.archive.client.ControlResponsePoller;
import io.aeron.archive.codecs.ControlResponseCode;
import io.aeron.archive.codecs.SourceLocation;
import io.aeron.archive.status.RecordingPos;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.MessageHeaderDecoder;
import io.aeron.cluster.codecs.SnapshotMarkerDecoder;
import io.aeron.cluster.codecs.SnapshotRecordingsDecoder;
import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.status.CountersReader;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.CommonContext.ENDPOINT_PARAM_NAME;
import static io.aeron.archive.client.AeronArchive.NULL_LENGTH;
import static io.aeron.cluster.ConsensusModule.Configuration.SNAPSHOT_TYPE_ID;

public class DynamicJoin implements AutoCloseable
{
    enum State
    {
        INIT,
        PASSIVE_FOLLOWER,
        SNAPSHOT_RETRIEVE,
        SNAPSHOT_LOAD,
        JOIN_CLUSTER,
        DONE
    }

    private final AeronArchive localArchive;
    private final MemberStatusAdapter memberStatusAdapter;
    private final MemberStatusPublisher memberStatusPublisher;
    private final ConsensusModule.Context ctx;
    private final ConsensusModuleAgent consensusModuleAgent;
    private final String[] clusterMembersStatusEndpoints;
    private final String memberEndpoints;
    private final String memberStatusEndpoint;
    private final String transferEndpoint;
    private final ArrayList<RecordingLog.Snapshot> leaderSnapshots = new ArrayList<>();
    private final long intervalMs;

    private ExclusivePublication clusterPublication;
    private State state = State.INIT;
    private ClusterMember[] clusterMembers;
    private ClusterMember leaderMember;
    private AeronArchive.AsyncConnect leaderArchiveAsyncConnect;
    private AeronArchive leaderArchive;
    private Subscription snapshotRetrieveSubscription;
    private Image snapshotRetrieveImage;
    private SnapshotReader snapshotReader;
    private long timeOfLastActivity = 0;
    private long correlationId = NULL_VALUE;
    private long snapshotRetrieveSubscriptionId = NULL_VALUE;
    private int memberId = NULL_VALUE;
    private int highMemberId = NULL_VALUE;
    private int clusterMembersSattusEndpointsCursor = 0;
    private int recordingIdCursor = 0;
    private int snapshotReplaySessionId = NULL_VALUE;

    public DynamicJoin(
        final String clusterMembersStatusEndpoints,
        final AeronArchive localArchive,
        final MemberStatusAdapter memberStatusAdapter,
        final MemberStatusPublisher memberStatusPublisher,
        final ConsensusModule.Context ctx,
        final ConsensusModuleAgent consensusModuleAgent)
    {
        final ClusterMember thisMember = ClusterMember.parseEndpoints(-1, ctx.memberEndpoints());

        this.localArchive = localArchive;
        this.memberStatusAdapter = memberStatusAdapter;
        this.memberStatusPublisher = memberStatusPublisher;
        this.ctx = ctx;
        this.consensusModuleAgent = consensusModuleAgent;
        this.intervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.dynamicJoinIntervalNs());
        this.memberEndpoints = ctx.memberEndpoints();
        this.memberStatusEndpoint = thisMember.memberFacingEndpoint();
        this.transferEndpoint = thisMember.transferEndpoint();
        this.clusterMembersStatusEndpoints = clusterMembersStatusEndpoints.split(",");

        final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
        memberStatusUri.put(
            ENDPOINT_PARAM_NAME, this.clusterMembersStatusEndpoints[clusterMembersSattusEndpointsCursor]);
        clusterPublication = ctx.aeron().addExclusivePublication(
            memberStatusUri.toString(), ctx.memberStatusStreamId());
    }

    public void close()
    {
        CloseHelper.close(clusterPublication);
        CloseHelper.close(snapshotRetrieveSubscription);
    }

    boolean isDone()
    {
        return State.DONE == state;
    }

    int doWork(final long nowMs)
    {
        int workCount = 0;
        workCount += memberStatusAdapter.poll();

        switch (state)
        {
            case INIT:
                workCount += init(nowMs);
                break;

            case PASSIVE_FOLLOWER:
                workCount += passiveFollower(nowMs);
                break;

            case SNAPSHOT_RETRIEVE:
                workCount += snapshotRetrieve(nowMs);
                break;

            case SNAPSHOT_LOAD:
                workCount += snapshotLoad(nowMs);
                break;

            case JOIN_CLUSTER:
                workCount += joinCluster(nowMs);
                break;
        }

        return workCount;
    }

    public void onClusterMembersChange(
        final long correlationId, final int leaderMemberId, final String activeMembers, final String passiveMembers)
    {
        if (State.INIT == state && correlationId == this.correlationId)
        {
            final ClusterMember[] passiveFollowers = ClusterMember.parse(passiveMembers);

            for (final ClusterMember follower : passiveFollowers)
            {
                if (memberStatusEndpoint.equals(follower.memberFacingEndpoint()))
                {
                    memberId = follower.id();

                    clusterMembers = ClusterMember.parse(activeMembers);
                    leaderMember = ClusterMember.findMember(clusterMembers, leaderMemberId);

                    if (null != leaderMember)
                    {
                        if (!leaderMember.memberFacingEndpoint().equals(
                            clusterMembersStatusEndpoints[clusterMembersSattusEndpointsCursor]))
                        {
                            clusterPublication.close();

                            final ChannelUri memberStatusUri = ChannelUri.parse(ctx.memberStatusChannel());
                            memberStatusUri.put(ENDPOINT_PARAM_NAME, leaderMember.memberFacingEndpoint());
                            clusterPublication = ctx.aeron().addExclusivePublication(
                                memberStatusUri.toString(), ctx.memberStatusStreamId());
                        }

                        final ChannelUri leaderArchiveUri = ChannelUri.parse(
                            ctx.archiveContext().controlRequestChannel());
                        final ChannelUri localArchiveUri = ChannelUri.parse(
                            ctx.archiveContext().controlResponseChannel());
                        leaderArchiveUri.put(ENDPOINT_PARAM_NAME, leaderMember.archiveEndpoint());

                        final AeronArchive.Context leaderArchiveCtx = new AeronArchive.Context()
                            .aeron(ctx.aeron())
                            .controlRequestChannel(leaderArchiveUri.toString())
                            .controlRequestStreamId(ctx.archiveContext().controlResponseStreamId())
                            .controlResponseChannel(localArchiveUri.toString())
                            .controlResponseStreamId(ctx.archiveContext().controlResponseStreamId());

                        leaderArchiveAsyncConnect = AeronArchive.asyncConnect(leaderArchiveCtx);

                        timeOfLastActivity = 0;
                        state = State.PASSIVE_FOLLOWER;
                    }
                    break;
                }
            }
        }
    }

    public void onSnapshotRecordings(
        final long correlationId, final SnapshotRecordingsDecoder snapshotRecordingsDecoder)
    {
        if (State.PASSIVE_FOLLOWER == state && correlationId == this.correlationId)
        {
            final SnapshotRecordingsDecoder.SnapshotsDecoder snapshotsDecoder = snapshotRecordingsDecoder.snapshots();

            if (snapshotsDecoder.count() > 0)
            {
                for (final SnapshotRecordingsDecoder.SnapshotsDecoder snapshot : snapshotsDecoder)
                {
                    if (snapshot.serviceId() <= ctx.serviceCount())
                    {
                        leaderSnapshots.add(new RecordingLog.Snapshot(
                            snapshot.recordingId(),
                            snapshot.leadershipTermId(),
                            snapshot.termBaseLogPosition(),
                            snapshot.logPosition(),
                            snapshot.timestamp(),
                            snapshot.serviceId()));
                    }
                }
            }

            timeOfLastActivity = 0;
            recordingIdCursor = 0;
            this.correlationId = NULL_VALUE;
            state = leaderSnapshots.isEmpty() ? State.JOIN_CLUSTER : State.SNAPSHOT_RETRIEVE;
        }
    }

    private int init(final long nowMs)
    {
        if (nowMs > (timeOfLastActivity + intervalMs))
        {
            correlationId = ctx.aeron().nextCorrelationId();

            if (memberStatusPublisher.addClusterMember(clusterPublication, correlationId, memberEndpoints))
            {
                timeOfLastActivity = nowMs;
                return 1;
            }
        }
        return 0;
    }

    private int passiveFollower(final long nowms)
    {
        if (nowms > (timeOfLastActivity + intervalMs))
        {
            correlationId = ctx.aeron().nextCorrelationId();

            if (memberStatusPublisher.snapshotRecordingQuery(clusterPublication, correlationId, memberId))
            {
                timeOfLastActivity = nowms;
                return 1;
            }
        }
        return 0;
    }

    private int snapshotRetrieve(final long nowMs)
    {
        int workCount = 0;

        if (null == leaderArchive)
        {
            leaderArchive = leaderArchiveAsyncConnect.poll();
            return (null == leaderArchive) ? 0 : 1;
        }

        if (null != snapshotReader)
        {
            if (snapshotReader.poll() == 0)
            {
                if (snapshotReader.isDone())
                {
                    final CountersReader countersReader = ctx.aeron().countersReader();
                    final int counterId = RecordingPos.findCounterIdBySession(countersReader, snapshotReplaySessionId);
                    final long recordingId = RecordingPos.getRecordingId(countersReader, counterId);

                    final RecordingLog.Snapshot snapshot = leaderSnapshots.get(recordingIdCursor);
                    ctx.recordingLog().appendSnapshot(
                        recordingId,
                        snapshot.leadershipTermId,
                        snapshot.termBaseLogPosition,
                        snapshot.logPosition,
                        snapshot.timestamp,
                        snapshot.serviceId);

                    snapshotRetrieveSubscription.close();
                    snapshotRetrieveSubscription = null;
                    snapshotRetrieveImage = null;
                    snapshotReader = null;
                    correlationId = NULL_VALUE;
                    snapshotReplaySessionId = NULL_VALUE;

                    if (++recordingIdCursor >= leaderSnapshots.size())
                    {
                        localArchive.stopRecording(snapshotRetrieveSubscriptionId);
                        state = State.SNAPSHOT_LOAD;
                        workCount++;
                    }
                }
                else if (snapshotRetrieveImage.isClosed())
                {
                    throw new ClusterException("retrieval of snapshot image ended unexpectedly");
                }
            }
            else
            {
                workCount++;
            }
        }
        else if (null == snapshotRetrieveImage && null != snapshotRetrieveSubscription)
        {
            snapshotRetrieveImage = snapshotRetrieveSubscription.imageBySessionId(snapshotReplaySessionId);
            if (null != snapshotRetrieveImage)
            {
                snapshotReader = new SnapshotReader(snapshotRetrieveImage);
                workCount++;
            }
        }
        else if (NULL_VALUE == correlationId)
        {
            final long replayId = ctx.aeron().nextCorrelationId();

            final RecordingLog.Snapshot snapshot = leaderSnapshots.get(recordingIdCursor);

            final ChannelUri replayChannelUri = ChannelUri.parse(ctx.replayChannel());
            replayChannelUri.put(CommonContext.ENDPOINT_PARAM_NAME, transferEndpoint);

            if (leaderArchive.archiveProxy().replay(
                snapshot.recordingId,
                0,
                NULL_LENGTH,
                replayChannelUri.toString(),
                ctx.replayStreamId(),
                replayId,
                leaderArchive.controlSessionId()))
            {
                this.correlationId = replayId;
                workCount++;
            }
        }
        else if (pollForResponse(leaderArchive, correlationId))
        {
            final int replaySessionId = (int)leaderArchive.controlResponsePoller().relevantId();
            final ChannelUri replayChannelUri = ChannelUri.parse(ctx.replayChannel());
            replayChannelUri.put(CommonContext.ENDPOINT_PARAM_NAME, transferEndpoint);
            replayChannelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(replaySessionId));
            final String replaySubscriptionChannel = replayChannelUri.toString();

            snapshotRetrieveSubscription = ctx.aeron().addSubscription(replaySubscriptionChannel, ctx.replayStreamId());
            snapshotRetrieveSubscriptionId = localArchive.startRecording(
                replaySubscriptionChannel, ctx.replayStreamId(), SourceLocation.REMOTE);
            workCount++;
        }

        return workCount;
    }

    private int snapshotLoad(final long nowMs)
    {
        // TODO: perform loading of snapshot and wait for done by the services, once done, go to join
        // TODO: loading of the snapshot will set the consensus module clusterMembers, etc.
        return 0;
    }

    private int joinCluster(final long nowMs)
    {
        // TODO: send join, create election, and go to done
        return 0;
    }

    private static boolean pollForResponse(final AeronArchive archive, final long correlationId)
    {
        final ControlResponsePoller poller = archive.controlResponsePoller();

        if (poller.poll() > 0 && poller.isPollComplete())
        {
            if (poller.controlSessionId() == archive.controlSessionId() &&
                poller.correlationId() == correlationId)
            {
                if (poller.code() == ControlResponseCode.ERROR)
                {
                    throw new ClusterException("archive response for correlationId=" + correlationId +
                        ", error: " + poller.errorMessage());
                }

                return true;
            }
        }

        return false;
    }

    private static class SnapshotReader implements ControlledFragmentHandler
    {
        private static final int FRAGMENT_LIMIT = 10;

        private boolean inSnapshot = false;
        private boolean isDone = false;
        private final MessageHeaderDecoder messageHeaderDecoder = new MessageHeaderDecoder();
        private final SnapshotMarkerDecoder snapshotMarkerDecoder = new SnapshotMarkerDecoder();
        private final Image image;

        SnapshotReader(final Image image)
        {
            this.image = image;
        }

        boolean isDone()
        {
            return isDone;
        }

        int poll()
        {
            return image.controlledPoll(this, FRAGMENT_LIMIT);
        }

        public Action onFragment(final DirectBuffer buffer, final int offset, final int length, final Header header)
        {
            messageHeaderDecoder.wrap(buffer, offset);

            if (messageHeaderDecoder.templateId() == SnapshotMarkerDecoder.TEMPLATE_ID)
            {
                snapshotMarkerDecoder.wrap(
                    buffer,
                    offset + MessageHeaderDecoder.ENCODED_LENGTH,
                    messageHeaderDecoder.blockLength(),
                    messageHeaderDecoder.version());

                final long typeId = snapshotMarkerDecoder.typeId();
                if (typeId != SNAPSHOT_TYPE_ID)
                {
                    throw new ClusterException("unexpected snapshot type: " + typeId);
                }

                switch (snapshotMarkerDecoder.mark())
                {
                    case BEGIN:
                        if (inSnapshot)
                        {
                            throw new ClusterException("already in snapshot");
                        }
                        inSnapshot = true;
                        return Action.CONTINUE;

                    case END:
                        if (!inSnapshot)
                        {
                            throw new ClusterException("missing begin snapshot");
                        }
                        isDone = true;
                        return Action.BREAK;
                }
            }

            return ControlledFragmentHandler.Action.CONTINUE;
        }
    }
}
