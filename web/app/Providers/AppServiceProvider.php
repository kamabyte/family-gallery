<?php

namespace App\Providers;

use App\Gallery\Fake\FakeGalleryCatalog;
use App\Gallery\GalleryCatalog;
use Carbon\CarbonImmutable;
use Illuminate\Support\ServiceProvider;
use InvalidArgumentException;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        // Per request: the fake catalog keeps the visitor's favourites in their session.
        $this->app->scoped(GalleryCatalog::class, fn ($app) => match (config('gallery.driver')) {
            'fake' => new FakeGalleryCatalog(
                session: $app['session.store'],
                seed: config('gallery.fake.seed'),
                today: CarbonImmutable::now(),
            ),
            default => throw new InvalidArgumentException('Unknown GALLERY_DRIVER: '.config('gallery.driver')),
        });
    }

    public function boot(): void
    {
        //
    }
}
