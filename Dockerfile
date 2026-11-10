FROM eclipse-temurin:21-jdk AS build

COPY . /dres-src
RUN cd /dres-src && \
  ./gradlew distTar && \
  mkdir dres-dist && \
  cd dres-dist && \
  tar xf ../backend/build/distributions/dres-dist.tar

FROM eclipse-temurin:21-jre

RUN mkdir /dres-data
COPY config.json /dres-data
COPY --from=build /dres-src/dres-dist /

EXPOSE 8080
EXPOSE 8443
ENTRYPOINT /dres-dist/bin/backend /dres-data/config.json
