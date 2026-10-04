<?php

namespace App\Http\Controllers;

use App\Gallery\Data\Album;
use App\Gallery\GalleryCatalog;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Inertia\Inertia;
use Inertia\Response;

class PhotoController extends Controller
{
    /**
     * Full-screen viewer. `from` says what the arrows step through — the
     * timeline, an album, a day or a search (GalleryCatalog::sequence()).
     */
    public function show(Request $request, GalleryCatalog $catalog, int $photo): Response
    {
        $item = $catalog->photo($photo) ?? abort(404);
        $context = $request->string('from')->toString() ?: null;

        $sequence = $catalog->sequence($context);
        $index = array_search($item->id, $sequence, true);
        if ($index === false) {
            // Opened from a context it doesn't belong to — fall back to the timeline.
            $context = null;
            $sequence = $catalog->sequence(null);
            $index = array_search($item->id, $sequence, true);
        }

        $neighbour = fn (int $at) => isset($sequence[$at]) ? $catalog->photo($sequence[$at])?->toArray() : null;

        return Inertia::render('viewer', [
            'photo' => $item->toArray(),
            'previous' => $neighbour($index - 1),
            'next' => $neighbour($index + 1),
            'position' => ['index' => $index + 1, 'total' => count($sequence)],
            'from' => $context,
            'albums' => array_map(fn (Album $a) => [
                'id' => $a->id,
                'type' => $a->type,
                'name' => $a->name,
            ], $catalog->albumsOf($item)),
        ]);
    }

    public function favorite(Request $request, GalleryCatalog $catalog, int $photo): RedirectResponse
    {
        $catalog->photo($photo) ?? abort(404);
        $favorite = $request->boolean('favorite');
        $catalog->setFavorite($photo, $favorite);

        Inertia::flash('toast', [
            'type' => 'success',
            'message' => $favorite ? 'Добавлено в избранное' : 'Убрано из избранного',
        ]);

        return back();
    }

    /** «В избранное» для нескольких выбранных кадров. */
    public function favoriteMany(Request $request, GalleryCatalog $catalog): RedirectResponse
    {
        $data = $request->validate([
            'ids' => ['required', 'array', 'max:5000'],
            'ids.*' => ['integer'],
            'favorite' => ['required', 'boolean'],
        ]);

        $count = 0;
        foreach (array_unique($data['ids']) as $id) {
            if ($catalog->photo($id) !== null) {
                $catalog->setFavorite($id, $data['favorite']);
                $count++;
            }
        }

        Inertia::flash('toast', [
            'type' => 'success',
            'message' => $data['favorite'] ? "В избранное: {$count}" : "Убрано из избранного: {$count}",
        ]);

        return back();
    }
}
