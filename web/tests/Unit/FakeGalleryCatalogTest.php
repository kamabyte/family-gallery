<?php

use App\Gallery\Fake\FakeGalleryCatalog;
use App\Gallery\Favorites;
use Carbon\CarbonImmutable;

function fakeCatalog(string $now, int $seed = 42): FakeGalleryCatalog
{
    return new FakeGalleryCatalog(
        Favorites::none(),
        $seed,
        CarbonImmutable::parse($now, 'Europe/Moscow'),
    );
}

it('generates the same library for the same seed and day', function () {
    $a = fakeCatalog('2026-10-04 12:00');
    $b = fakeCatalog('2026-10-04 12:00');

    expect($a->photo(500)->toArray())->toBe($b->photo(500)->toArray())
        ->and($a->buckets())->toBe($b->buckets());
});

it('keeps existing photos unchanged as the day goes on', function () {
    $morning = fakeCatalog('2026-10-04 08:05');
    $evening = fakeCatalog('2026-10-04 23:30');

    // New photos appear "today", but every id that existed keeps its content.
    expect($evening->status()['photos'] + $evening->status()['videos'])
        ->toBeGreaterThanOrEqual($morning->status()['photos'] + $morning->status()['videos']);
    foreach ([1, 100, 2000] as $id) {
        expect($evening->photo($id)->toArray())->toBe($morning->photo($id)->toArray());
    }
});

it('never dates a photo in the future', function () {
    $catalog = fakeCatalog('2026-10-04 10:00');
    $newest = $catalog->month('2026-10')[0];

    expect($newest->takenAt->lessThanOrEqualTo(CarbonImmutable::parse('2026-10-04 10:00', 'Europe/Moscow')))->toBeTrue();
});

it('finds memories on the same calendar day in earlier years', function () {
    $memories = fakeCatalog('2026-10-04 12:00')->memories();

    expect($memories)->not->toBeEmpty();
    foreach ($memories as $memory) {
        expect($memory['date'])->toBe((2026 - $memory['years_ago']).'-10-04');
    }
});

it('derives albums from metadata and counts them consistently', function () {
    $catalog = fakeCatalog('2026-10-04 12:00');

    foreach ($catalog->albums() as $album) {
        expect(count($catalog->albumPhotos($album->id)))->toBe($album->count);
    }
    $yearsTotal = collect($catalog->albums())->where('type', 'year')->sum('count');
    expect($yearsTotal)->toBe(collect($catalog->buckets())->sum('count'));
});
