FROM node:18-bookworm-slim

# Chromium e servidor gráfico virtual no próprio ambiente de execução.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       chromium \
       xvfb \
       fonts-liberation \
       dbus-x11 \
    && chromium --version \
    && command -v Xvfb \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package.json ./
RUN npm install --omit=dev

# O entrypoint collector.js carrega collector.cjs; ambos são necessários.
COPY collector.js ./
COPY collector.cjs ./
COPY start.sh ./
RUN chmod +x ./start.sh

ENV PUPPETEER_SKIP_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV DISPLAY=:99

EXPOSE 10000

CMD ["./start.sh"]
