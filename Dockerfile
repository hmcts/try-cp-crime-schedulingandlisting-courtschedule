 # renovate: datasource=github-releases depName=microsoft/ApplicationInsights-Java
ARG APP_INSIGHTS_AGENT_VERSION=3.7.9
FROM hmctsprod.azurecr.io/base/java:25-distroless

COPY lib/applicationinsights.json /opt/app/
COPY build/libs/apim-try-slc.jar /opt/app/

EXPOSE 8080
CMD [ "apim-try-slc.jar" ]
