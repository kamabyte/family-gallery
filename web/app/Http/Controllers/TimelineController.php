<?php

namespace App\Http\Controllers;

use App\Gallery\Data\Photo;
use App\Gallery\GalleryCatalog;
use Inertia\Inertia;
use Inertia\Response;

class TimelineController extends Controller
{
    /** Months sent with the page, so the first screen paints without a round trip. */
    private const EAGER_MONTHS = 2;

    public function __invoke(GalleryCatalog $catalog): Response
    {
        $buckets = $catalog->buckets();

        $months = [];
        foreach (array_slice($buckets, 0, self::EAGER_MONTHS) as $bucket) {
            $months[$bucket['month']] = array_map(fn (Photo $p) => $p->toArray(), $catalog->month($bucket['month']));
        }

        return Inertia::render('timeline', [
            'buckets' => $buckets,
            'months' => $months,
            'memories' => array_map(fn (array $memory) => [
                'years_ago' => $memory['years_ago'],
                'date' => $memory['date'],
                'count' => count($memory['photos']),
                'cover' => (collect($memory['photos'])->first(fn (Photo $p) => ! $p->isVideo) ?? $memory['photos'][0])->toArray(),
            ], $catalog->memories()),
        ]);
    }
}
