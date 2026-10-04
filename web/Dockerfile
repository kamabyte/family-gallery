# Family gallery web app: Laravel (PHP-FPM) + nginx in one container (serversideup/php,
# same base as MyTube's api). Image ghcr.io/kamabyte/family-gallery-web is built by
# .github/workflows/web-image.yml and run by Dokploy from deploy/compose.yml.
#
# Stateless for now: the prototype reads FakeGalleryCatalog, sessions and cache are
# files inside the container (a redeploy resets favourites — fine for a prototype).

# --- Frontend (Inertia + React + Vite) ---------------------------------------
FROM node:22-slim AS assets
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --loglevel=error
COPY vite.config.ts tsconfig.json components.json ./
COPY resources ./resources
RUN npm run types:check && npm run build

# --- Application -------------------------------------------------------------
FROM serversideup/php:8.5-fpm-nginx-trixie
USER root
# intl — Russian dates and plurals in PHP.
RUN install-php-extensions intl

USER www-data
WORKDIR /var/www/html
COPY --chown=www-data:www-data composer.json composer.lock ./
RUN composer install --no-dev --no-interaction --no-progress --no-scripts --no-autoloader --prefer-dist
COPY --chown=www-data:www-data . .
COPY --chown=www-data:www-data --from=assets /app/public/build ./public/build
RUN composer dump-autoload --no-dev --optimize --classmap-authoritative \
 && php artisan package:discover --ansi

# Defaults; everything about the environment is in deploy/compose.yml.
ENV PHP_OPCACHE_ENABLE=1 \
    PHP_MEMORY_LIMIT=256M \
    PHP_FPM_PM_MAX_CHILDREN=8 \
    HEALTHCHECK_PATH=/up \
    LOG_CHANNEL=stderr
