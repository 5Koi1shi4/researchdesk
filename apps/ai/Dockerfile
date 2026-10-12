FROM python:3.12-alpine@sha256:b64631e04e4920160c50fbe8d8df828f7f35f06f425cb44aa09bca53e708a35a
WORKDIR /app
COPY apps/ai/requirements.lock requirements.lock
COPY apps/ai/wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links=/wheels -r requirements.lock && rm -rf /wheels
COPY apps/ai/app.py app.py
COPY apps/ai/generated generated
COPY apps/ai/ingestion ingestion
COPY apps/ai/worker worker
USER 10001:10001
EXPOSE 8000
CMD ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
