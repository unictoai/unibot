FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv/unibot-cloud

COPY pyproject.toml README.md ./
COPY unibot_cloud ./unibot_cloud
RUN pip install --no-cache-dir . && mkdir -p /srv/unibot-cloud/data

ENV CLOUD_DB=/srv/unibot-cloud/data/cloud.db
VOLUME ["/srv/unibot-cloud/data"]
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=3).status == 200 else 1)"

CMD ["unibot-cloud", "--host", "0.0.0.0", "--port", "8787"]
