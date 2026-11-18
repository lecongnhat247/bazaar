FROM python:3.14.4-slim-bookworm
WORKDIR /app
RUN useradd --uid 1001 --create-home --shell /usr/sbin/nologin bazaar
COPY services/recommendation-service/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/recommendation-service/schema.sql /app/schema.sql
COPY services/recommendation-service/src /app/src
USER bazaar
ENV PYTHONPATH=/app/src
ENV PYTHONUNBUFFERED=1
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/ready')"
CMD ["uvicorn", "recommendation.main:app", "--host", "0.0.0.0", "--port", "8080"]
