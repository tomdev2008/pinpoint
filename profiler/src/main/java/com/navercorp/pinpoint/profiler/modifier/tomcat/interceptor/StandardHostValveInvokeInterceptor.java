/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.profiler.modifier.tomcat.interceptor;

import java.util.Enumeration;

import com.navercorp.pinpoint.bootstrap.config.Filter;
import com.navercorp.pinpoint.bootstrap.config.ProfilerConfig;
import com.navercorp.pinpoint.bootstrap.context.*;
import com.navercorp.pinpoint.bootstrap.interceptor.*;
import com.navercorp.pinpoint.bootstrap.sampler.SamplingFlagUtils;
import com.navercorp.pinpoint.bootstrap.util.NetworkUtils;
import com.navercorp.pinpoint.bootstrap.util.NumberUtils;
import com.navercorp.pinpoint.bootstrap.util.StringUtils;
import com.navercorp.pinpoint.common.AnnotationKey;
import com.navercorp.pinpoint.common.ServiceType;
import com.navercorp.pinpoint.profiler.context.*;

import javax.servlet.http.HttpServletRequest;

/**
 * @author emeroad
 */
public class StandardHostValveInvokeInterceptor extends SpanSimpleAroundInterceptor implements TargetClassLoader {

    private final boolean isTrace = logger.isTraceEnabled();
    private Filter<String> excludeUrlFilter;

    public StandardHostValveInvokeInterceptor() {
        super(StandardHostValveInvokeInterceptor.class);
    }

    @Override
    protected void doInBeforeTrace(RecordableTrace trace, Object target, Object[] args) {
        final HttpServletRequest request = (HttpServletRequest) args[0];
        trace.markBeforeTime();
        if (trace.canSampled()) {
//            trace.recordServiceType(ServiceType.TOMCAT);

            final String requestURL = request.getRequestURI();
            trace.recordRpcName(requestURL);

            final int port = request.getServerPort();
            final String endPoint = request.getServerName() + ":" + port;
            trace.recordEndPoint(endPoint);

            final String remoteAddr = request.getRemoteAddr();
            trace.recordRemoteAddress(remoteAddr);
        }

        if (!trace.isRoot()) {
            recordParentInfo(trace, request);
        }
    }

    @Override
    protected Trace createTrace(Object target, Object[] args) {
        final HttpServletRequest request = (HttpServletRequest) args[0];
        final String requestURI = request.getRequestURI();
        if (excludeUrlFilter.filter(requestURI)) {
            if (isTrace) {
                logger.trace("filter requestURI:{}", requestURI);
            }
            return null;
        }
        
        
        // check sampling flag from client. If the flag is false, do not sample this request. 
        final boolean sampling = samplingEnable(request);
        if (!sampling) {
            // Even if this transaction is not a sampling target, we have to create Trace object to mark 'not sampling'.
            // For example, if this transaction invokes rpc call, we can add parameter to tell remote node 'don't sample this transaction'  
            final TraceContext traceContext = getTraceContext();
            final Trace trace = traceContext.disableSampling();
            if (isDebug) {
                logger.debug("remotecall sampling flag found. skip trace requestUrl:{}, remoteAddr:{}", request.getRequestURI(), request.getRemoteAddr());
            }
            return trace;
        }


        final TraceId traceId = populateTraceIdFromRequest(request);
        if (traceId != null) {
            // TODO Maybe we should decide to trace or not even if the sampling flag is true to prevent too many requests are traced.
            final TraceContext traceContext = getTraceContext();
            final Trace trace = traceContext.continueTraceObject(traceId);
            
            if (trace.canSampled()) {
                if (isDebug) {
                    logger.debug("TraceID exist. continue trace. traceId:{}, requestUrl:{}, remoteAddr:{}", new Object[] {traceId, request.getRequestURI(), request.getRemoteAddr()});
                }
            } else {
                if (isDebug) {
                    logger.debug("TraceID exist. camSampled is false. skip trace. traceId:{}, requestUrl:{}, remoteAddr:{}", new Object[] {traceId, request.getRequestURI(), request.getRemoteAddr()});
                }
            }
            return trace;
        } else {
            final TraceContext traceContext = getTraceContext();
            final Trace trace = traceContext.newTraceObject();
            if (trace.canSampled()) {
                if (isDebug) {
                    logger.debug("TraceID not exist. start new trace. requestUrl:{}, remoteAddr:{}", request.getRequestURI(), request.getRemoteAddr());
                }
            } else {
                if (isDebug) {
                    logger.debug("TraceID not exist. camSampled is false. skip trace. requestUrl:{}, remoteAddr:{}", request.getRequestURI(), request.getRemoteAddr());
                }
            }
            return trace;
        }
    }



    private void recordParentInfo(RecordableTrace trace, HttpServletRequest request) {
        String parentApplicationName = request.getHeader(Header.HTTP_PARENT_APPLICATION_NAME.toString());
        if (parentApplicationName != null) {
            trace.recordAcceptorHost(NetworkUtils.getHostFromURL(request.getRequestURL().toString()));

            final String type = request.getHeader(Header.HTTP_PARENT_APPLICATION_TYPE.toString());
            final short parentApplicationType = NumberUtils.parseShort(type, ServiceType.UNDEFINED.getCode());
            trace.recordParentApplication(parentApplicationName, parentApplicationType);
        }
    }

    @Override
    protected void doInAfterTrace(RecordableTrace trace, Object target, Object[] args, Object result, Throwable throwable) {
        if (trace.canSampled()) {
            final HttpServletRequest request = (HttpServletRequest) args[0];
            final String parameters = getRequestParameter(request, 64, 512);
            if (parameters != null && parameters.length() > 0) {
                trace.recordAttribute(AnnotationKey.HTTP_PARAM, parameters);
            }

            trace.recordApi(getMethodDescriptor());
        }
        trace.recordException(throwable);
        trace.markAfterTime();
    }

    /**
     * Pupulate source trace from HTTP Header.
     *
     * @param request
     * @return TraceId when it is possible to get a transactionId from Http header. if not possible return null
     */
    private TraceId populateTraceIdFromRequest(HttpServletRequest request) {

        String transactionId = request.getHeader(Header.HTTP_TRACE_ID.toString());
        if (transactionId != null) {

            long parentSpanID = NumberUtils.parseLong(request.getHeader(Header.HTTP_PARENT_SPAN_ID.toString()), SpanId.NULL);
            long spanID = NumberUtils.parseLong(request.getHeader(Header.HTTP_SPAN_ID.toString()), SpanId.NULL);
            short flags = NumberUtils.parseShort(request.getHeader(Header.HTTP_FLAGS.toString()), (short) 0);

            final TraceId id = getTraceContext().createTraceId(transactionId, parentSpanID, spanID, flags);
            if (isDebug) {
                logger.debug("TraceID exist. continue trace. {}", id);
            }
            return id;
        } else {
            return null;
        }
    }

    private boolean samplingEnable(HttpServletRequest request) {
        // optional value
        final String samplingFlag = request.getHeader(Header.HTTP_SAMPLED.toString());
        if (isDebug) {
            logger.debug("SamplingFlag:{}", samplingFlag);
        }
        return SamplingFlagUtils.isSamplingFlag(samplingFlag);
    }

    private String getRequestParameter(HttpServletRequest request, int eachLimit, int totalLimit) {
        Enumeration<?> attrs = request.getParameterNames();
        final StringBuilder params = new StringBuilder(64);

        while (attrs.hasMoreElements()) {
            if (params.length() != 0 ) {
                params.append('&');
            }
            // skip appending parameters if parameter size is bigger than totalLimit
            if (params.length() > totalLimit) {
                params.append("...");
                return  params.toString();
            }
            String key = attrs.nextElement().toString();
            params.append(StringUtils.drop(key, eachLimit));
            params.append("=");
            Object value = request.getParameter(key);
            if (value != null) {
                params.append(StringUtils.drop(StringUtils.toString(value), eachLimit));
            }
        }
        return params.toString();
    }

    @Override
    public void setTraceContext(TraceContext traceContext) {
        super.setTraceContext(traceContext);

        ProfilerConfig profilerConfig = traceContext.getProfilerConfig();

        this.excludeUrlFilter = profilerConfig.getTomcatExcludeUrlFilter();
    }
}
