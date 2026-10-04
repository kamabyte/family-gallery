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
        $user = $request->user();

        return [
            ...parent::share($request),
            'auth' => [
                'user' => $user ? [
                    'id' => $user->id,
                    'name' => $user->name,
                    'email' => $user->email,
                    'role' => $user->role->value,
                    'role_label' => $user->role->label(),
                ] : null,
                'can' => [
                    'admin' => (bool) $user?->can('admin'),
                    'family' => (bool) $user?->can('family'),
                ],
            ],
            // Lazily: partial reloads (a favourite toggle) don't recompute them.
            'sidebarPlaces' => fn () => $user === null ? [] : collect(app(GalleryCatalog::class)->albums())
                ->where('type', 'place')
                ->take(8)
                ->map(fn (Album $a) => ['id' => $a->id, 'name' => $a->name, 'count' => $a->count])
                ->values()
                ->all(),
            'library' => fn () => $user === null ? null : app(GalleryCatalog::class)->status(),
        ];
    }
}
