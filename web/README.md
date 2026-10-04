# Web: семейная галерея в браузере

Laravel 13 + Inertia 3 + React 19 + Tailwind 4 + shadcn/ui — тот же стек, что у
MyTube (`mytube/api`). Оформление — от веб-клиента MyTube (тёмная тема как у
Apple TV, «стеклянные» шапка и панель вкладок), приёмы галереи — от Immich:
лента ровными рядами по дням, полоса времени справа, «В этот день»,
просмотрщик на весь экран со сведениями о кадре.

Все страницы читают библиотеку через интерфейс `App\Gallery\GalleryCatalog`
(`GALLERY_DRIVER`):

- `catalog` — **на сервере.** `IndexerCatalog` читает каталог, который публикует
  индексатор (`<библиотека>/.gallery/manifest.json` → `catalogs/index-<rev>.db`),
  только на чтение: новую ревизию видно со следующего запроса. Миниатюры, превью и
  видеопрокси — `GET /media/<путь>` (только точный путь вида
  `.gallery/thumbs/xx/<sha256>_t|p.webp|_v.mp4`), оригиналы — `GET /photos/{id}/download`
  (роль «Семья» и выше). Отдаёт файлы nginx по `X-Accel-Redirect`
  (`docker/nginx/gallery.conf`), Laravel только проверяет вход.
- `fake` — для разработки и тестов: сгенерированная библиотека (~4,5 тыс. кадров),
  картинки с picsum.photos.

## Запуск

```bash
composer install && npm install
cp .env.example .env && php artisan key:generate
npm run build            # или npm run dev — с горячей перезагрузкой
touch database/database.sqlite && php artisan migrate
php artisan gallery:user admin@example.home        # первая учётная запись — администратор
php artisan serve --port=8010
```

http://localhost:8010.

## На сервере

http://photos.home.internal — сервис `web` стека Dokploy `gallery` (проект
`family-gallery`, рядом с индексатором), см. [`deploy/compose.yml`](../deploy/compose.yml).
Образ `ghcr.io/kamabyte/family-gallery-web` собирает `.github/workflows/web-image.yml`
(сначала Pest и Pint) на каждый push в `web/`. Выкатка — Deploy стека в Dokploy;
откат — `WEB_TAG=sha-…` во вкладке Environment. Там же секрет `APP_KEY`.

База (`/data/database.sqlite`) — на хосте в `/srv/family-gallery/web`, владелец
1000:3000; миграции — при старте контейнера. Её дамп снимает ночная резервная копия.
Первая учётная запись:

```bash
ssh server 'sudo docker exec -it $(sudo docker ps -qf name=family-gallery-.*-web) php artisan gallery:user <email>'
```

## Вход и роли

Как в awgkeys: Laravel Fortify, без регистрации и без сброса по почте — учётные
записи заводит администратор (страница «Настройки → Пользователи» или
`php artisan gallery:user <email>`; без `--role` новая запись — администратор), он же
выдаёт новый пароль, показанный один раз. Двухфакторная аутентификация (TOTP) —
в «Настройки → Безопасность». Ключей доступа (passkeys) нет: WebAuthn требует
HTTPS, а `photos.home.internal` — HTTP.

| Роль | Что можно |
|---|---|
| Администратор | всё, плюс учётные записи |
| Семья | смотреть, избранное (у каждого своё), скачивание |
| Гость | только смотреть |

Права проверяет сервер (гейты `admin` и `family`), интерфейс лишь прячет лишнее.
База — SQLite: учётные записи, сессии, избранное.

## Что есть

| Страница | Что |
|---|---|
| `/` Фото | лента по месяцам и дням; месяцы догружаются из `GET /api/timeline/{YYYY-MM}` по мере прокрутки; полоса лет справа; «В этот день» |
| `/albums` | альбомы из метаданных, как у индексатора: места, камеры, годы + «Избранное» и «Видео» |
| `/albums/{id}` | альбом с шапкой из обложки и слайд-шоу |
| `/photos/{id}?from=…` | просмотрщик: ← → / свайп, Esc, `i` — сведения, `f` — избранное, пробел — слайд-шоу. `from` задаёт, что листается: `album:<id>`, `day:<дата>`, `search:<запрос>`, без него — лента |
| `/search?q=` | по городу, стране, камере, году, месяцу: «Казань июль», «видео 2024» |

Выбор нескольких кадров: кружок при наведении (на телефоне — долгое нажатие) или
кружок у заголовка дня; шапка превращается в панель действий (у гостя выбора нет).
Избранное — у каждого своё, в базе; «Скачать» пока только показывает уведомление.

## Тесты

```bash
php artisan test         # Pest: страницы, API, просмотрщик, избранное, поиск, генератор
npm run types:check
```
