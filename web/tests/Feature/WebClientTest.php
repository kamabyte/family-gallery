<?php

use App\Gallery\GalleryCatalog;
use App\Models\User;
use Inertia\Testing\AssertableInertia as Assert;

beforeEach(function () {
    // Семья — обычная роль; права гостя и администратора — в AccessTest.
    $this->actingAs(User::factory()->create());
    $this->catalog = app(GalleryCatalog::class);
});

it('renders the timeline with buckets, eager months and memories', function () {
    $buckets = $this->catalog->buckets();

    $this->get('/')
        ->assertOk()
        ->assertInertia(fn (Assert $page) => $page
            ->component('timeline')
            ->has('buckets', count($buckets))
            ->has("months.{$buckets[0]['month']}")
            ->has("months.{$buckets[1]['month']}")
            ->missing("months.{$buckets[2]['month']}")
            ->has('memories')
            ->has('library.revision')
            ->has('sidebarPlaces'));
});

it('serves a month of the timeline as JSON, newest first', function () {
    $month = $this->catalog->buckets()[3]['month'];

    $photos = $this->getJson("/api/timeline/{$month}")
        ->assertOk()
        ->assertJsonPath('month', $month)
        ->assertJsonStructure(['photos' => [['id', 'type', 'taken_at', 'date', 'width', 'height', 'thumb', 'preview', 'place', 'camera']]])
        ->json('photos');

    expect(collect($photos)->pluck('date')->every(fn ($date) => str_starts_with($date, $month)))->toBeTrue()
        ->and(collect($photos)->pluck('taken_at')->all())->toBe(collect($photos)->pluck('taken_at')->sortDesc()->values()->all());
});

it('rejects a malformed month', function () {
    $this->getJson('/api/timeline/latest')->assertNotFound();
});

it('steps through the album the viewer was opened from', function () {
    $album = collect($this->catalog->albums())->firstWhere('type', 'place');
    $ids = $this->catalog->sequence("album:{$album->id}");

    $this->get("/photos/{$ids[1]}?from=album:{$album->id}")
        ->assertOk()
        ->assertInertia(fn (Assert $page) => $page
            ->component('viewer')
            ->where('photo.id', $ids[1])
            ->where('previous.id', $ids[0])
            ->where('next.id', $ids[2])
            ->where('position', ['index' => 2, 'total' => $album->count])
            ->where('from', "album:{$album->id}"));
});

it('falls back to the timeline when the photo is not in the given context', function () {
    $outsider = collect($this->catalog->month($this->catalog->buckets()[0]['month']))
        ->first(fn ($p) => $p->city === null);

    $this->get("/photos/{$outsider->id}?from=album:place-kazan")
        ->assertOk()
        ->assertInertia(fn (Assert $page) => $page->where('from', null)->where('photo.id', $outsider->id));
});

it('returns 404 for unknown photos and albums', function () {
    $this->get('/photos/999999')->assertNotFound();
    $this->get('/albums/place-atlantis')->assertNotFound();
});

it('keeps favourites per person and lists them in the favourites album', function () {
    $photo = collect($this->catalog->month($this->catalog->buckets()[0]['month']))->first(fn ($p) => ! $p->favorite);

    $this->from('/')->put("/photos/{$photo->id}/favorite", ['favorite' => true])->assertRedirect('/');

    $this->get('/albums/favorites')
        ->assertInertia(fn (Assert $page) => $page
            ->component('album')
            ->where('photos.0.id', $photo->id)
            ->where('photos.0.favorite', true));

    $this->put("/photos/{$photo->id}/favorite", ['favorite' => false]);
    $this->get("/photos/{$photo->id}")->assertInertia(fn (Assert $page) => $page->where('photo.favorite', false));
});

it('adds several selected photos to favourites at once', function () {
    $ids = collect($this->catalog->month($this->catalog->buckets()[1]['month']))
        ->reject(fn ($p) => $p->favorite)->take(3)->pluck('id')->all();

    $this->from('/')->put('/photos/favorite', ['ids' => $ids, 'favorite' => true])->assertRedirect('/');

    foreach ($ids as $id) {
        $this->get("/photos/{$id}")->assertInertia(fn (Assert $page) => $page->where('photo.favorite', true));
    }
});

it('validates the bulk favourite request', function () {
    $this->put('/photos/favorite', ['ids' => 'all'])->assertSessionHasErrors(['ids', 'favorite']);
});

it('searches by every word and offers albums matching any word', function () {
    $this->get('/search?q='.urlencode('Казань июль'))
        ->assertOk()
        ->assertInertia(fn (Assert $page) => $page
            ->component('search')
            ->where('query', 'Казань июль')
            ->where('albums.0.name', 'Казань')
            ->where('photos', fn ($photos) => collect($photos)->every(
                fn ($p) => $p['place']['city'] === 'Казань' && substr($p['date'], 5, 2) === '07',
            )));
});

it('suggests places and cameras on an empty search', function () {
    $this->get('/search')->assertInertia(fn (Assert $page) => $page
        ->where('query', '')
        ->has('photos', 0)
        ->where('suggestions', fn ($albums) => collect($albums)->isNotEmpty()
            && collect($albums)->every(fn ($a) => in_array($a['type'], ['place', 'camera']))));
});

it('groups albums as the app does: smart, places, cameras, years', function () {
    $this->get('/albums')->assertInertia(fn (Assert $page) => $page
        ->component('albums')
        ->where('albums', function ($albums) {
            $types = collect($albums)->pluck('type')->unique()->values()->all();

            return $types === ['smart', 'place', 'camera', 'year'];
        }));
});
