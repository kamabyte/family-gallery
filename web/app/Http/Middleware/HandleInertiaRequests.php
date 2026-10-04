<?php

namespace App\Http\Middleware;

use App\Gallery\Data\Album;
use App\Gallery\GalleryCatalog;
use Illuminate\Http\Request;
use Inertia\Middleware;

class HandleInertiaRequests extends Middleware
{
    protected $rootView = 'app';

    /**
     * @return array<string, mixed>
     */
    public function share(Request $request): array
    {
        return [
            ...parent::share($request),
            // Lazily: partial reloads (a favourite toggle) don't recompute them.
            'sidebarPlaces' => fn () => collect(app(GalleryCatalog::class)->albums())
                ->where('type', 'place')
                ->take(8)
                ->map(fn (Album $a) => ['id' => $a->id, 'name' => $a->name, 'count' => $a->count])
                ->values()
                ->all(),
            'library' => fn () => app(GalleryCatalog::class)->status(),
        ];
    }
}
