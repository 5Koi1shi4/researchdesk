FROM golang:1.24.8-alpine@sha256:3d78beb141d98f42337f1252ecf2a5f20374109929a4c3f6817f9e4179cc0ae5 AS build
WORKDIR /src
COPY source.tar.gz /tmp/source.tar.gz
RUN echo '45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841  /tmp/source.tar.gz' | sha256sum -c - && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
COPY gomod-cache/ /opt/go-proxy/
ARG GOPROXY=https://proxy.golang.org
ARG GOSUMDB=sum.golang.org
ENV CGO_ENABLED=0 GOTOOLCHAIN=local
RUN go mod download && go mod verify
RUN go build -trimpath -buildvcs=false -ldflags='-s -w -X github.com/minio/minio/cmd.Version=2025-10-15T17:29:55Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-10-15T17-29-55Z -X github.com/minio/minio/cmd.CommitID=9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a -X github.com/minio/minio/cmd.ShortCommitID=9e49d5e7a648f' -o /minio .
FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
COPY --from=build /minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /licenses/MinIO-AGPL-3.0.txt
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
LABEL org.opencontainers.image.source="https://github.com/minio/minio" org.opencontainers.image.revision="9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a" org.opencontainers.image.licenses="AGPL-3.0"
RUN mkdir /data && chown 10001:10001 /data
USER 10001:10001
ENTRYPOINT ["minio"]
