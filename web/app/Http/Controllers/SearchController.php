<?php

namespace App\Http\Controllers;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use Illuminate\Http\Request;
use Inertia\Inertia;
use Inertia\Response;

class SearchController extends Controller
{
    private const MAX_PHOTOS = 500;

    public function __invoke(Request $request, GalleryCatalog $catalog): Response
    {
        $query = trim($request->string('q')->toString());
        $results = $catalog->search($query);

        // With an empty query the page offers a few starting points instead.
        $suggestions = $query === ''
            ? collect($catalog->albums())->whereIn('type', ['place', 'camera'])->take(10)->values()
            : collect();

        return Inertia::render('search', [
            'query' => $query,
            'albums' => array_map(fn (Album $a) => $a->toArray(), array_slice($results['albums'], 0, 12)),
            'photos' => array_map(fn (Photo $p) => $p->toArray(), array_slice($results['photos'], 0, self::MAX_PHOTOS)),
            'total' => count($results['photos']),
            'suggestions' => $suggestions->map(fn (Album $a) => $a->toArray())->all(),
        ]);
    }
}
