FROM python:3.12-bookworm

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=/app \
    PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt \
    && python -m playwright install --with-deps chromium \
    && useradd --create-home --uid 10001 crawler \
    && mkdir /work && chown crawler:crawler /work
COPY requirements-cloud.txt ./
RUN pip install --no-cache-dir -r requirements-cloud.txt
COPY src ./src
COPY jev ./jev
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["python", "-m", "src.cli"]
