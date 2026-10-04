<?php

namespace App\Http\Controllers;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use Illuminate\Http\Request;
use Inertia\Inertia;
use Inertia\Response;

class AlbumController extends Controller
{
    public function index(Request $request, GalleryCatalog $catalog): Response
    {
        return Inertia::render('albums', [
            'albums' => array_values(array_map(
                fn (Album $a) => $a->toArray(),
                array_filter($catalog->albums(), fn (Album $a) => $a->id !== 'favorites' || $request->user()->isFamily()),
            )),
        ]);
    }

    public function show(Request $request, GalleryCatalog $catalog, string $album): Response
    {
        // У гостя избранного нет — и альбома «Избранное» тоже.
        abort_if($album === 'favorites' && ! $request->user()->isFamily(), 404);
        $item = $catalog->album($album) ?? abort(404);

        return Inertia::render('album', [
            'album' => $item->toArray(),
            'photos' => array_map(fn (Photo $p) => $p->toArray(), $catalog->albumPhotos($album)),
        ]);
    }
}
