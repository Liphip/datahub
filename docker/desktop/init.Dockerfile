FROM python:3.14.7-slim-trixie
COPY --chmod=755 docker/desktop/init.sh /desktop-init.sh
ENTRYPOINT ["/desktop-init.sh"]
