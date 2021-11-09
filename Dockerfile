FROM gradle:7.2-jdk11 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle installDist --no-daemon

FROM public.ecr.aws/amazoncorretto/amazoncorretto:11 AS runtime
RUN mkdir /opt/app
COPY --from=build /home/gradle/src/build/install/Fasusa /opt/app
WORKDIR /opt/app
CMD ["bash", "./bin/Fasusa"]
