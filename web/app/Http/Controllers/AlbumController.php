<?php

namespace App\Http\Controllers;

use App\Gallery\Data\Album;
use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use Inertia\Inertia;
use Inertia\Response;

class AlbumController extends Controller
{
    public function index(GalleryCatalog $catalog): Response
    {
        return Inertia::render('albums', [
            'albums' => array_map(fn (Album $a) => $a->toArray(), $catalog->albums()),
        ]);
    }

    public function show(GalleryCatalog $catalog, string $album): Response
    {
        $item = $catalog->album($album) ?? abort(404);

        return Inertia::render('album', [
            'album' => $item->toArray(),
            'photos' => array_map(fn (Photo $p) => $p->toArray(), $catalog->albumPhotos($album)),
        ]);
    }
}
