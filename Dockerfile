FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
RUN mkdir -p /app/uploads && chown -R app:app /app
# Named exactly, never globbed: the pom's <finalName> pins this filename, and COPY with more than
# one matching source into a file destination is a hard Docker error.
COPY --chown=app:app target/yvoke.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
