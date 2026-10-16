FROM python:3.12-slim

WORKDIR /srv/app
COPY pyproject.toml ./
COPY app ./app
COPY sql ./sql
RUN pip install --no-cache-dir .

EXPOSE 8100
CMD ["python", "-m", "uvicorn", "app.api_main:app", "--host", "0.0.0.0", "--port", "8100"]
