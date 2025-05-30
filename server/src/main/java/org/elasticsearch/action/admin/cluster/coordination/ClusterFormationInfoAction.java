/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.action.admin.cluster.coordination;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.LegacyActionRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.coordination.ClusterFormationFailureHelper;
import org.elasticsearch.cluster.coordination.Coordinator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.injection.guice.Inject;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Objects;

/**
 * This class is used to fetch the ClusterFormationState from another node. The ClusterFormationState provides information about why that
 * node thinks that cluster formation has failed.
 */
public class ClusterFormationInfoAction extends ActionType<ClusterFormationInfoAction.Response> {

    public static final ClusterFormationInfoAction INSTANCE = new ClusterFormationInfoAction();
    public static final String NAME = "internal:cluster/formation/info";

    private ClusterFormationInfoAction() {
        super(NAME);
    }

    public static class Request extends LegacyActionRequest {

        public Request() {}

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        public Request(StreamInput in) throws IOException {
            super(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
        }

        @Override
        public boolean equals(Object o) {
            // There are no parameters, so all instances of this class are equal
            if (this == o) {
                return true;
            }
            return o != null && getClass() == o.getClass();
        }

        @Override
        public int hashCode() {
            // There are no parameters, so all instances of this class are equal
            return 1;
        }

    }

    public static class Response extends ActionResponse {

        private final ClusterFormationFailureHelper.ClusterFormationState clusterFormationState;

        public Response(StreamInput in) throws IOException {
            clusterFormationState = new ClusterFormationFailureHelper.ClusterFormationState(in);
        }

        public Response(ClusterFormationFailureHelper.ClusterFormationState clusterFormationState) {
            this.clusterFormationState = clusterFormationState;
        }

        public ClusterFormationFailureHelper.ClusterFormationState getClusterFormationState() {
            return clusterFormationState;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            clusterFormationState.writeTo(out);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Response response = (Response) o;
            return clusterFormationState.equals(response.clusterFormationState);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clusterFormationState);
        }
    }

    /**
     * This transport action fetches the ClusterFormationState from a remote node.
     */
    public static class TransportAction extends HandledTransportAction<Request, Response> {
        private final Coordinator coordinator;

        @Inject
        public TransportAction(TransportService transportService, ActionFilters actionFilters, Coordinator coordinator) {
            super(
                ClusterFormationInfoAction.NAME,
                transportService,
                actionFilters,
                Request::new,
                transportService.getThreadPool().executor(ThreadPool.Names.CLUSTER_COORDINATION)
            );
            this.coordinator = coordinator;
        }

        @Override
        protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
            listener.onResponse(new Response(coordinator.getClusterFormationState()));
        }
    }

}
