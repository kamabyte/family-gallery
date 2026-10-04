<?php

namespace App\Providers;

use App\Gallery\Fake\FakeGalleryCatalog;
use App\Gallery\Favorites;
use App\Gallery\GalleryCatalog;
use App\Models\User;
use Carbon\CarbonImmutable;
use Illuminate\Support\Facades\Gate;
use Illuminate\Support\ServiceProvider;
use InvalidArgumentException;

class AppServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        // Per request: favourites are the signed-in viewer's own.
        $this->app->scoped(Favorites::class, fn ($app) => new Favorites(fn () => $app['auth']->user()));

        $this->app->scoped(GalleryCatalog::class, fn ($app) => match (config('gallery.driver')) {
            'fake' => new FakeGalleryCatalog(
                favorites: $app->make(Favorites::class),
                seed: config('gallery.fake.seed'),
                today: CarbonImmutable::now(),
            ),
            default => throw new InvalidArgumentException('Unknown GALLERY_DRIVER: '.config('gallery.driver')),
        });
    }

    public function boot(): void
    {
        // admin — учётные записи; family — избранное и скачивание (не гостю).
        Gate::define('admin', fn (User $user) => $user->isAdmin());
        Gate::define('family', fn (User $user) => $user->isFamily());
    }
}
