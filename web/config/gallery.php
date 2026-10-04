<?php

return [
    /*
     * Where the library comes from:
     *   catalog — the catalog the indexer publishes into the library
     *             (<root>/.gallery/manifest.json → catalogs/index-<rev>.db),
     *             App\Gallery\Catalog\IndexerCatalog;
     *   fake    — a generated library for development and tests,
     *             App\Gallery\Fake\FakeGalleryCatalog.
     */
    'driver' => env('GALLERY_DRIVER', 'fake'),

    // Library root (the indexer's --source), mounted read-only in the container.
    'root' => env('GALLERY_ROOT', '/photos'),

    /*
     * How thumbnails, previews, video proxies and originals reach the browser.
     * With nginx in front (the Docker image) Laravel checks access and answers with
     * X-Accel-Redirect: <prefix>/<path>, and nginx sends the file — see
     * docker/nginx/gallery.conf. Without it (php artisan serve, tests) PHP streams
     * the file itself.
     */
    'accel_redirect' => env('GALLERY_ACCEL_REDIRECT') ? rtrim(env('GALLERY_ACCEL_REDIRECT'), '/') : null,

    'fake' => [
        'seed' => (int) env('GALLERY_FAKE_SEED', 20261004),
    ],
];
