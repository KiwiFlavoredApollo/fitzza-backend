FROM 414901227428.dkr.ecr.ap-northeast-2.amazonaws.com/fitzza/recommendation-service@sha256:d9ae634c8119cb5e5e36ed4d1311115c350f2519c25bba6e03737ccd704b3c7b
USER 0
WORKDIR /pipeline
COPY crawler/requirements-cloud.txt ./requirements-cloud.txt
RUN pip install --no-cache-dir -r requirements-cloud.txt
COPY indexer/requirements.txt ./requirements-indexer.txt
RUN pip install --no-cache-dir -r requirements-indexer.txt
COPY index_to_qdrant.py ./index_to_qdrant.py
COPY crawler/src/review_gate.py ./review_gate.py
COPY indexer/entrypoint.py ./entrypoint.py
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 HF_HOME=/work/huggingface
USER 10001:10001
WORKDIR /work
ENTRYPOINT ["python", "/pipeline/entrypoint.py"]
