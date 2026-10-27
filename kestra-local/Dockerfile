# Kestra 2.0.7 plus a Java 8 runtime for the NEI jars.
# Kestra itself runs on Java 21. The NEIs are Java 8 code: CLICR needs Nashorn (removed in Java 15), and the
# NEI wrapper prepares the Macro Server status-code tables with a Java 8-only reflection step. The flows run the
# wrapper with ${NEI_JAVA:-java}, so this image points NEI_JAVA at the Java 8 runtime.
FROM eclipse-temurin:8-jre AS jre8

FROM kestra/kestra:v2.0.7
COPY --from=jre8 /opt/java/openjdk /opt/java8
ENV NEI_JAVA=/opt/java8/bin/java

# The image runs as user kestra (uid 1000) and has no /app/storage. A named volume mounted on a folder that does
# not exist in the image is created owned by root, and Kestra then fails at start-up with
# "AccessDeniedException: /app/storage/plugins". Creating the mount points here, owned by kestra, makes Docker
# initialise new volumes with that owner.
USER root
# /mnt/shared_data is BST's order-folder root; the CLICR workflow YAMLs use that path literally.
RUN mkdir -p /app/storage /mnt/shared_data && chown -R kestra:kestra /app/storage /mnt/shared_data
USER kestra

# Kestra's own UI rebuilt with the "IO / MML" execution tab (./build-ui.sh -> ui-overlay/), served instead of the
# UI inside the jar: kestra-launch.sh puts /app/ui-overlay in front of /app/kestra on the classpath.
USER root
COPY ui-overlay/ui /app/ui-overlay/ui
COPY kestra-launch.sh /app/kestra-launch.sh
RUN sed -i 's/\r$//' /app/kestra-launch.sh && chmod 755 /app/kestra-launch.sh && chown -R kestra:kestra /app/ui-overlay
USER kestra
ENTRYPOINT ["/app/kestra-launch.sh"]
