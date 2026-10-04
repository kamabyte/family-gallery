# Family gallery workers for the NAS: service.py runs indexer.py on a schedule and whenever
# new files land in Imports. Image ghcr.io/kamabyte/family-gallery-workers is built by
# .github/workflows/workers-image.yml and run by Dokploy from deploy/compose.yml.
# The library (originals + .gallery/) is bind-mounted at /photos; nothing else is stateful.
FROM python:3.12-slim-trixie

# ffmpeg/ffprobe — video posters, metadata and TV proxies; tzdata — EXIF dates carry no
# zone and are read in local time, so TZ must resolve (the server itself runs in UTC).
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && python -c "import pillow_heif, reverse_geocoder, pycountry"

COPY indexer.py refresh_metadata.py service.py ./

# 1000:3000 = lenar:media on the server — owner and group of the library and of every
# file that arrives through the Photos SMB share.
RUN groupadd --gid 3000 media && useradd --uid 1000 --gid media --no-create-home --home-dir /app indexer
USER 1000:3000

ENV PYTHONUNBUFFERED=1 \
    TZ=Europe/Moscow \
    GALLERY_SOURCE=/photos

HEALTHCHECK --interval=60s --timeout=10s --start-period=30s --retries=3 \
  CMD ["python", "/app/service.py", "--health"]
CMD ["python", "/app/service.py"]
