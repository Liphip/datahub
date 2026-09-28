FROM python:3.14.7-alpine3.24 AS builder

WORKDIR /workspace

COPY docs-site/requirements.txt /workspace/requirements.txt
RUN pip install --no-cache-dir -r /workspace/requirements.txt

COPY docs-site/ /workspace/
# Developer documentation is not duplicated in docs-site: the canonical docs/ tree is copied in at
# build time, so the published pages always match the repository (see CONTRIBUTING.md).
COPY docs/ /workspace/docs/reference/
# Zensical reads mkdocs.yml natively (Material for MkDocs reaches end of life on 2026-11-05)
RUN zensical build -f mkdocs.yml

FROM nginx:1.30.5-alpine3.24
COPY --from=builder /workspace/site /usr/share/nginx/html
