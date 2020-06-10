package org.wso2.micro.integrator.prometheus.handler.handler;

import io.prometheus.client.Histogram;
import io.prometheus.client.hotspot.DefaultExports;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.AbstractExtendedSynapseHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.rest.API;
import org.apache.synapse.rest.RESTUtils;
import org.apache.synapse.transport.nhttp.NhttpConstants;
import org.wso2.micro.integrator.core.internal.MicroIntegratorBaseConstants;
import org.wso2.micro.integrator.prometheus.handler.util.MetricConstants;

import java.util.HashMap;

public class PrometheusHandler extends AbstractExtendedSynapseHandler {

    PrometheusReporter prometheusReporter = new PrometheusReporter();
    private static Log log = LogFactory.getLog(PrometheusHandler.class);
    private static final String DELIMITER = "/";
    private static final String EMPTY = "";
    private static final String SPLIT = ":";
    private RESTUtils restUtils = new RESTUtils();

    @Override
    public boolean handleRequestInFlow(MessageContext synCtx) {

        System.out.println("Request Inflow");
        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) synCtx)
                .getAxis2MessageContext();

        int portOffset = 0;
        int internalHttpApiPort = 0;
        int serviceInvokePort = 0;

        // Get the Internal HTTP Inbound Endpoint port
        if ((null != System.getProperty(MetricConstants.PORT_OFFSET)) &&
                (null != (MetricConstants.INTERNAL_HTTP_API_PORT))) {
            portOffset = Integer.parseInt(System.getProperty((MetricConstants.PORT_OFFSET)));
            internalHttpApiPort = Integer.parseInt(synCtx.getEnvironment().getSynapseConfiguration().
                    getProperty((MetricConstants.INTERNAL_HTTP_API_PORT)));
            internalHttpApiPort = internalHttpApiPort + portOffset;

        } else {
            log.warn("Port Offset or Internal HTTP API port is not set.");
        }

        if (null == synCtx.getProperty("HAS_EXECUTED_FLOW")) {
            if ((null != synCtx.getProperty(SynapseConstants.PROXY_SERVICE))) {
                String proxyName = axis2MessageContext.getAxisService().getName();

                incrementProxyCount(proxyName);
                startTimers(synCtx, proxyName, SynapseConstants.PROXY_SERVICE_TYPE, null);
            } else if (null != synCtx.getProperty(SynapseConstants.IS_INBOUND)) {
                String inboundEndpointName = synCtx.getProperty(SynapseConstants.INBOUND_ENDPOINT_NAME).toString();

                incrementInboundEndPointCount(inboundEndpointName);
                startTimers(synCtx, inboundEndpointName, MetricConstants.INBOUND_ENDPOINT,
                        null);
            } else if ((null != axis2MessageContext.getProperty("TransportInURL") &&
                    !axis2MessageContext.getProperty("TransportInURL").toString().contains("services"))) {

                try {
                    if (null != ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                            getProperty(NhttpConstants.SERVICE_PREFIX)) {
                        String servicePort = ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                                getProperty(NhttpConstants.SERVICE_PREFIX).toString();
                        servicePort = servicePort.substring((servicePort.lastIndexOf(':') + 1),
                                servicePort.lastIndexOf(DELIMITER));
                        serviceInvokePort = Integer.parseInt(servicePort);

                    }

                    if ((serviceInvokePort != internalHttpApiPort)) {
                        String context = axis2MessageContext.getProperty(MetricConstants.TRANSPORT_IN_URL).
                                toString();
                        String apiInvocationUrl = axis2MessageContext.getProperty(MetricConstants.SERVICE_PREFIX).
                                toString() + context.replaceFirst(DELIMITER, EMPTY);
                        String apiName = getApiName(context, synCtx);

                        if (apiName != null) {
                            incrementAPICount(apiName, apiInvocationUrl);
                            startTimers(synCtx, apiName, SynapseConstants.FAIL_SAFE_MODE_API, apiInvocationUrl);
                        }
                    }
                } catch (Exception ex) {
                    log.error("Error in retrieving Service Invoke Port");
                }
            }
        }
        return true;

    }

    @Override
    public boolean handleRequestOutFlow(MessageContext synCtx) {

        return true;
    }

    @Override
    public boolean handleResponseInFlow(MessageContext synCtx) {

        return true;
    }

    @Override
    public boolean handleResponseOutFlow(MessageContext synCtx) {

        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) synCtx)
                .getAxis2MessageContext();

        int portOffset = 0;
        int internalHttpApiPort = 0;
        int serviceInvokePort = 0;

        // Get the Internal HTTP Inbound Endpoint port
        if ((null != System.getProperty(MetricConstants.PORT_OFFSET)) &&
                (null != MetricConstants.INTERNAL_HTTP_API_PORT)) {
            portOffset = Integer.parseInt(System.getProperty(MetricConstants.PORT_OFFSET));
            internalHttpApiPort = Integer.parseInt(synCtx.getEnvironment().getSynapseConfiguration().
                    getProperty(MetricConstants.INTERNAL_HTTP_API_PORT));
            internalHttpApiPort = internalHttpApiPort + portOffset;

        } else {
            log.warn("Port Offset or Internal HTTP API port is not set.");
        }

        if ((null != synCtx.getProperty("proxy.name"))) {
            stopTimers((Histogram.Timer) synCtx.getProperty(MetricConstants.PROXY_LATENCY_TIMER), synCtx);
        }  else if(null == axis2MessageContext.getProperty("TransportInURL")) {

            // Get the port used in service invoking
            try {
                if (null != ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                        getProperty(NhttpConstants.SERVICE_PREFIX)) {
                    String servicePort = ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                            getProperty(NhttpConstants.SERVICE_PREFIX).toString();

                    if (null != servicePort) {
                        servicePort = servicePort.substring((servicePort.lastIndexOf(':') + 1),
                                servicePort.lastIndexOf(DELIMITER));
                        serviceInvokePort = Integer.parseInt(servicePort);
                    }
                }

                if (null != synCtx.getProperty(SynapseConstants.IS_INBOUND) && (serviceInvokePort != internalHttpApiPort)) {
                    stopTimers((Histogram.Timer) synCtx.
                            getProperty(MetricConstants.INBOUND_ENDPOINT_LATENCY_TIMER), synCtx);
                }
              if ((serviceInvokePort != internalHttpApiPort)) {
                    stopTimers((Histogram.Timer) synCtx.getProperty(MetricConstants.API_LATENCY_TIMER), synCtx);
                }

            } catch (Exception e) {
                log.error("Error in retrieving Service Invoke Port");
            }
        }
        return true;
    }

    @Override
    public boolean handleError(MessageContext synCtx) {
          org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) synCtx)
                .getAxis2MessageContext();

        // Get the Internal HTTP Inbound Endpoint port
        int portOffset = Integer.parseInt(System.getProperty(MetricConstants.PORT_OFFSET));
        int internalHttpApiPort = Integer.parseInt(synCtx.getEnvironment().getSynapseConfiguration().
                getProperty(MetricConstants.INTERNAL_HTTP_API_PORT));
        internalHttpApiPort = internalHttpApiPort + portOffset;
        int serviceInvokePort = 0;

        if (null == synCtx.getProperty(SynapseConstants.IS_ERROR_COUNT_ALREADY_PROCESSED)) {
            if (null != synCtx.getProperty("proxy.name")) {
                String name = synCtx.getProperty(MetricConstants.PROXY_NAME).toString();
                incrementProxyErrorCount(name);
                stopTimers((Histogram.Timer) synCtx.getProperty(MetricConstants.PROXY_LATENCY_TIMER), synCtx);
            } else if ((null != synCtx.getProperty(SynapseConstants.IS_INBOUND) &&
                    synCtx.getProperty(SynapseConstants.IS_INBOUND).toString().equals("true")) ||
                    ((null != axis2MessageContext.getProperty("TransportInURL")) &&
                            !axis2MessageContext.getProperty("TransportInURL").toString().contains("services"))) {
                // Get the port used in service invoking
                if (null != ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                        getProperty(NhttpConstants.SERVICE_PREFIX)) {
                    String servicePort = ((Axis2MessageContext) synCtx).getAxis2MessageContext().
                            getProperty(NhttpConstants.SERVICE_PREFIX).toString();
                    servicePort = servicePort.substring((servicePort.lastIndexOf(':') + 1),
                            servicePort.lastIndexOf(DELIMITER));
                    serviceInvokePort = Integer.parseInt(servicePort);
                }
                if ((null != synCtx.getProperty(SynapseConstants.IS_INBOUND))) {
                    String inboundEndpointName = synCtx.getProperty(MetricConstants.INBOUND_ENDPOINT_NAME).
                            toString();
                    incrementInboundEndpointErrorCount(inboundEndpointName);
                    stopTimers((Histogram.Timer) synCtx.getProperty
                            (MetricConstants.INBOUND_ENDPOINT_LATENCY_TIMER), synCtx);
                } else if (null != synCtx.getProperty(MetricConstants.SYNAPSE_REST_API) &&
                        (serviceInvokePort != internalHttpApiPort)) {
                    String context = axis2MessageContext.getProperty(MetricConstants.TRANSPORT_IN_URL).
                            toString();
                    String apiInvocationUrl = axis2MessageContext.getProperty(MetricConstants.SERVICE_PREFIX).
                            toString() + context.replaceFirst(DELIMITER, EMPTY);
                    String apiName = getApiName(context, synCtx);

                    incrementApiErrorCount(apiName, apiInvocationUrl);
                    stopTimers((Histogram.Timer) synCtx.getProperty(MetricConstants.API_LATENCY_TIMER), synCtx);
                }
            }
        }
        synCtx.setProperty(SynapseConstants.IS_ERROR_COUNT_ALREADY_PROCESSED, true);

        return true;
    }

    @Override
    public boolean handleInit() {
        PrometheusMetricCreator prometheusMetricCreator = new PrometheusMetricCreator();
        prometheusMetricCreator.createProxyServiceMetric();
        prometheusMetricCreator.createAPIServiceMetric();
        prometheusMetricCreator.createInboundEndpointMetric();
        prometheusMetricCreator.createProxyServiceErrorMetric();
        prometheusMetricCreator.createApiErrorMetric();
        prometheusMetricCreator.createInboundEndpointErrorMetric();

        String host = System.getProperty(MicroIntegratorBaseConstants.LOCAL_IP_ADDRESS);
        String port = System.getProperty(MetricConstants.HTTP_PORT);
        String javaVersion = System.getProperty(MetricConstants.JAVA_VERSION);
        String javaHome = System.getProperty(MetricConstants.JAVA_HOME);

        DefaultExports.initialize();
        prometheusReporter.serverUp(host, port, javaVersion, javaHome);

        return true;
    }

    @Override
    public boolean handleStopServer() {
        String host = System.getProperty(MicroIntegratorBaseConstants.LOCAL_IP_ADDRESS);
        String port = System.getProperty(MetricConstants.HTTP_PORT);
        String javaVersion = System.getProperty(MetricConstants.JAVA_VERSION);
        String javaHome = System.getProperty(MetricConstants.JAVA_HOME);

       // DefaultExports.initialize();
        prometheusReporter.serverUp(host, port, javaVersion, javaHome);

        return true;
    }

    @Override
    public boolean handleDeployArtifacts(String artifactName, String artifactType, String startTime) {
        prometheusReporter.serviceUp(artifactName, artifactType, startTime);
        return true;
    }

    @Override
    public boolean handleUnDeployArtifacts(String artifactName, String artifactType, String startTime) {

        return true;
    }

    private void startTimers(MessageContext synCtx, String serviceName, String serviceType, String apiInvocationUrl) {

        if (null == synCtx.getProperty("HAS_EXECUTED_FLOW")) {
            switch (serviceType) {
                case SynapseConstants.PROXY_SERVICE_TYPE:
                    HashMap map = new HashMap();
                    String[] val = {serviceName, serviceType};
                    map.put(MetricConstants.PROXY_LATENCY_SECONDS, val);

                    synCtx.setProperty(MetricConstants.PROXY_LATENCY_TIMER,
                            prometheusReporter.getTimer(MetricConstants.PROXY_LATENCY_SECONDS, map));
                    break;
                case MetricConstants.INBOUND_ENDPOINT:
                    HashMap map3 = new HashMap();
                    String[] val3 = {serviceName, serviceType};
                    map3.put(MetricConstants.INBOUND_ENDPOINT_LATENCY_SECONDS, val3);

                    synCtx.setProperty(MetricConstants.INBOUND_ENDPOINT_LATENCY_TIMER,
                            prometheusReporter.getTimer(MetricConstants.INBOUND_ENDPOINT_LATENCY_SECONDS, map3));
                    break;
                case SynapseConstants.FAIL_SAFE_MODE_API:
                    HashMap map2 = new HashMap();
                    String[] val2 = {serviceName, serviceType, apiInvocationUrl};
                    map2.put(MetricConstants.API_LATENCY_SECONDS, val2);

                    synCtx.setProperty(MetricConstants.API_LATENCY_TIMER,
                            prometheusReporter.getTimer(MetricConstants.API_LATENCY_SECONDS, map2));
                    break;
            }
        }
    }

    private void stopTimers(Histogram.Timer timer, MessageContext synCtx) {

        if (null == synCtx.getProperty(SynapseConstants.IS_ERROR_COUNT_ALREADY_PROCESSED)) {
            if (null != timer) {
                prometheusReporter.observeTime(timer);
            }
        }
    }

    private void incrementProxyCount(String name) {
        HashMap map = new HashMap();
        map.put(MetricConstants.PROXY_REQUEST_COUNT_TOTAL, new String[] {name, SynapseConstants.PROXY_SERVICE_TYPE});
        prometheusReporter.incrementCount(MetricConstants.PROXY_REQUEST_COUNT_TOTAL, map);
    }

     private void incrementAPICount(String name, String apiInvocationUrl) {
        HashMap map = new HashMap();
        map.put(MetricConstants.API_REQUEST_COUNT_TOTAL, new String[] {name, SynapseConstants.FAIL_SAFE_MODE_API, apiInvocationUrl});
        prometheusReporter.incrementCount(MetricConstants.API_REQUEST_COUNT_TOTAL, map);
    }

    private void incrementInboundEndPointCount(String name) {
        HashMap map = new HashMap();
        map.put(MetricConstants.INBOUND_ENDPOINT_REQUEST_COUNT_TOTAL, new String[] {name, MetricConstants.INBOUND_ENDPOINT});
        prometheusReporter.incrementCount(MetricConstants.INBOUND_ENDPOINT_REQUEST_COUNT_TOTAL, map);
    }

    private void incrementProxyErrorCount(String name) {
        HashMap map = new HashMap();
        map.put(MetricConstants.PROXY_REQUEST_COUNT_ERROR_TOTAL, new String[] {name, SynapseConstants.PROXY_SERVICE_TYPE});
        prometheusReporter.incrementCount(MetricConstants.PROXY_REQUEST_COUNT_ERROR_TOTAL, map);
    }

    private void incrementApiErrorCount(String name, String apiInvocationUrl) {
        HashMap map = new HashMap();
        map.put(MetricConstants.API_REQUEST_COUNT_ERROR_TOTAL, new String[]{name, SynapseConstants.FAIL_SAFE_MODE_API, apiInvocationUrl});
        prometheusReporter.incrementCount(MetricConstants.API_REQUEST_COUNT_ERROR_TOTAL, map);
    }

    private void incrementInboundEndpointErrorCount(String name) {
        HashMap map = new HashMap();
        map.put(MetricConstants.INBOUND_ENDPOINT_REQUEST_COUNT_ERROR_TOTAL, new String[]{name, MetricConstants.INBOUND_ENDPOINT});
        prometheusReporter.incrementCount(MetricConstants.INBOUND_ENDPOINT_REQUEST_COUNT_ERROR_TOTAL, map);
    }

    private String getApiName(String contextPath, MessageContext synCtx) {
        String apiName = null;
        for (API api : synCtx.getEnvironment().getSynapseConfiguration().getAPIs()) {
            if (restUtils.getRESTApiName(contextPath, api.getContext())) {
                apiName = api.getName();
                if (api.getVersionStrategy().getVersion() != null && !"".equals(api.getVersionStrategy().
                        getVersion())) {
                    apiName = apiName + ":v" + api.getVersionStrategy().getVersion();
                }
                synCtx.setProperty(MetricConstants.IS_ALREADY_PROCESSED_REST_API, true);
                synCtx.setProperty(MetricConstants.PROCESSED_API, api);
            }
        }
        return apiName;
    }
}
