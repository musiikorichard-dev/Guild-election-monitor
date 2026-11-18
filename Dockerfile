FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY Main.java .
RUN javac --add-modules jdk.httpserver -Xlint:all -d /build Main.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build /app/build
COPY web /app/web
ENV BIND_ADDRESS=0.0.0.0
ENV DATA_FILE=/var/data/makerere-election-monitor.tsv
ENV SESSION_COOKIE_SECURE=true
EXPOSE 10000
CMD ["java", "--add-modules", "jdk.httpserver", "-cp", "/app/build", "Main"]
