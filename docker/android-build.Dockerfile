# Build environment for the Android app: JDK 21 + Android SDK. Nothing is installed on the host.
#
# Everything is pinned so a rebuild gets the same bits: the base image by digest, the SDK command
# line tools by SHA-256 (matches the SHA-1 Google publishes in repository2-3.xml), SDK packages by
# version. To update, change a pin on purpose and record why. The pinned base image gets no
# automatic security updates; it only builds the APK, nothing in it ships.
FROM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a

ARG CMDLINE_TOOLS=16111833
ARG CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8
ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
 && echo "${CMDLINE_TOOLS_SHA256}  /tmp/tools.zip" | sha256sum -c - \
 && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-37.2" "build-tools;37.0.0" >/dev/null \
 # Builds run as the host user, who needs to write AGP's SDK caches. AGP may not download
 # components by itself (android.builder.sdkDownload=false in gradle.properties).
 && chmod -R a+rwX $ANDROID_HOME

WORKDIR /src
