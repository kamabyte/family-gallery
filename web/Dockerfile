# Family gallery web app: Laravel (PHP-FPM) + nginx in one container (serversideup/php,
# like MyTube's api and awgkeys). Image ghcr.io/kamabyte/family-gallery-web is built by
# .github/workflows/web-image.yml and run by Dokploy from deploy/compose.yml.
#
# State lives outside the image: SQLite (accounts, sessions, favourites) in /data, a bind
# mount of /srv/family-gallery/web. The library itself is the fake catalog for now.

# --- Build: the frontend needs PHP too -----------------------------------------
# @laravel/vite-plugin-wayfinder calls `php artisan wayfinder:generate` during vite build,
# so the build runs in a PHP image with node (same as awgkeys).
FROM serversideup/php:8.5-cli-trixie AS build
USER root
COPY --from=node:22-trixie-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:22-trixie-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && install-php-extensions intl
WORKDIR /var/www/html
COPY composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-progress --no-scripts --no-autoloader --prefer-dist
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --loglevel=error
COPY . ./
RUN composer dump-autoload --no-dev --optimize --classmap-authoritative \
 && cp .env.example .env && php artisan key:generate --force --quiet \
 && php artisan package:discover --ansi \
 && npm run build && npm run types:check \
 && rm -rf node_modules .env public/hot

# --- Application -----------------------------------------------------------------
FROM serversideup/php:8.5-fpm-nginx-trixie
USER root
# intl — Russian dates and plurals; sqlite3 — for a look at the database by hand.
RUN install-php-extensions intl \
 && apt-get update \
 && apt-get install -y --no-install-recommends sqlite3 \
 && rm -rf /var/lib/apt/lists/*

# 1000:3000 = lenar:media on the server, like the rest of /srv/family-gallery: the
# database on the host belongs to them. Ids taken in the base image are freed first.
RUN for id in 1000 3000; do \
      u="$(getent passwd "$id" | cut -d: -f1)"; [ -n "$u" ] && [ "$u" != www-data ] && usermod -u "6$id" "$u"; \
      g="$(getent group "$id" | cut -d: -f1)"; [ -n "$g" ] && [ "$g" != www-data ] && groupmod -g "6$id" "$g"; \
    done; \
    docker-php-serversideup-set-id www-data 1000:3000 \
 && docker-php-serversideup-set-file-permissions --owner 1000:3000 --service nginx

COPY --chmod=755 docker/40-gallery-database.sh /etc/entrypoint.d/40-gallery-database.sh
USER www-data
WORKDIR /var/www/html
COPY --chown=www-data:www-data --from=build /var/www/html ./

# Defaults; everything about the environment is in deploy/compose.yml.
ENV PHP_OPCACHE_ENABLE=1 \
    PHP_MEMORY_LIMIT=256M \
    PHP_FPM_PM_MAX_CHILDREN=8 \
    HEALTHCHECK_PATH=/up \
    LOG_CHANNEL=stderr
