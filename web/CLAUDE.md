# web/ — Laravel + Inertia + React

Prototype web client of the family gallery. Same stack and conventions as
`~/Code/personal/mytube/api` (Laravel 13, inertia-laravel 3, React 19, Tailwind 4,
shadcn new-york, Pest); UI copy is Russian, like the Android client.

- All data goes through `App\Gallery\GalleryCatalog` (bound in `AppServiceProvider`,
  scoped per request). `FakeGalleryCatalog` is the only driver for now. Its shapes mirror
  the indexer catalog (`workers/indexer.py`, schema v4) — keep them aligned so a real
  driver can replace it without touching pages.
- The fake library must stay deterministic: every item draws from its own
  `Mt19937` stream keyed by its timestamp, and ids count from the oldest item. Never
  `break` out of a generation loop on "now" — it shifts every later draw (see
  `tests/Unit/FakeGalleryCatalogTest.php`).
- Pages live in `resources/js/pages/*.tsx`; `app.tsx` gives every page `AppLayout`
  except `viewer`, which is full-screen.
- Auth mirrors `~/Code/personal/awgkeys/web`: Fortify (login, 2FA, password confirm; no
  registration, no email reset, no passkeys — WebAuthn needs HTTPS), `App\Enums\Role`
  (admin/member/guest), gates `admin` and `family`, Wayfinder route helpers (generated,
  gitignored). Favourites are per user (`favorites` table via `App\Gallery\Favorites`,
  which resolves the user on every call — never capture the user at construction).
- Timeline months load lazily from `GET /api/timeline/{YYYY-MM}`; the page sends only
  `buckets` plus the first two months.

```bash
php artisan test && npm run types:check && vendor/bin/pint --test
```
