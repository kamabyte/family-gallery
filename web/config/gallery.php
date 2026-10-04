<?php

return [
    /*
     * Where the library comes from. Only "fake" exists for now: a generated
     * library for the prototype (App\Gallery\Fake\FakeGalleryCatalog). The real
     * driver will read the catalog the indexer publishes onto the Photos share.
     */
    'driver' => env('GALLERY_DRIVER', 'fake'),

    'fake' => [
        'seed' => (int) env('GALLERY_FAKE_SEED', 20261004),
    ],
];
