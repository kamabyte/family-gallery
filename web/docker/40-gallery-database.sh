#!/bin/sh
# Пустая база SQLite при первом запуске: миграции (50-laravel-automations)
# создают таблицы, но не сам файл. Каталог /data — bind mount с хоста
# (/srv/family-gallery/web), владелец 1000:3000.
set -eu

[ "${AUTORUN_ENABLED:-false}" = "true" ] || exit 0
db="${DB_DATABASE:-}"
[ -n "${db}" ] || exit 0

if [ ! -f "${db}" ]; then
  if [ ! -w "$(dirname "${db}")" ]; then
    echo "gallery-database: $(dirname "${db}") недоступен для записи — база не создана" >&2
    exit 1
  fi
  touch "${db}"
  echo "gallery-database: создана пустая база ${db}"
fi
