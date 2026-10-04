<?php

namespace App\Http\Controllers\Api;

use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;

/** One month of the timeline; the page asks for it as the month scrolls into view. */
class TimelineMonthController extends Controller
{
    public function __invoke(GalleryCatalog $catalog, string $month): JsonResponse
    {
        return response()->json([
            'month' => $month,
            'photos' => array_map(fn (Photo $p) => $p->toArray(), $catalog->month($month)),
        ]);
    }
}
