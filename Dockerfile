FROM openjdk:11

WORKDIR /code

COPY . /code

RUN apt-get update && apt-get install -y git python socat

EXPOSE 8079 7687 7474 7786 7575

CMD socat tcp-listen:7786,reuseaddr,fork tcp:localhost:7687 & socat tcp-listen:7575,reuseaddr,fork tcp:localhost:7474 & ./gradlew run
