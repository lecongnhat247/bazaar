FROM mcr.microsoft.com/dotnet/sdk:10.0.404 AS build
WORKDIR /src
COPY services/analytics-service/Analytics.csproj services/analytics-service/Analytics.csproj
RUN dotnet restore services/analytics-service/Analytics.csproj
COPY services/analytics-service/ services/analytics-service/
RUN dotnet publish services/analytics-service/Analytics.csproj -c Release -o /app --no-restore

FROM mcr.microsoft.com/dotnet/aspnet:10.0.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 bazaar \
    && useradd --system --uid 1001 --gid bazaar --home-dir /app --shell /usr/sbin/nologin bazaar
WORKDIR /app
COPY --from=build --chown=bazaar:bazaar /app .
USER bazaar
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:8080
ENTRYPOINT ["dotnet", "Analytics.dll"]
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/health/ready || exit 1
