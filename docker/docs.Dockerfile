FROM python:3.12-alpine AS builder

WORKDIR /workspace

COPY docs-site/requirements.txt /workspace/requirements.txt
RUN pip install --no-cache-dir -r /workspace/requirements.txt

COPY docs-site/ /workspace/
RUN mkdocs build --site-dir /workspace/site

FROM nginx:alpine
COPY --from=builder /workspace/site /usr/share/nginx/html
