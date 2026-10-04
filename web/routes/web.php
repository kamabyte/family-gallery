<?php

use App\Http\Controllers\AlbumController;
use App\Http\Controllers\Api\TimelineMonthController;
use App\Http\Controllers\PhotoController;
use App\Http\Controllers\SearchController;
use App\Http\Controllers\TimelineController;
use App\Http\Controllers\UserController;
use Illuminate\Support\Facades\Route;

// Всё — только после входа. Гостю — смотреть; семье — ещё избранное и скачивание.
Route::middleware('auth')->group(function () {
    Route::get('/', TimelineController::class)->name('timeline');
    Route::get('/photos/{photo}', [PhotoController::class, 'show'])->whereNumber('photo')->name('photos.show');
    Route::get('/albums', [AlbumController::class, 'index'])->name('albums.index');
    Route::get('/albums/{album}', [AlbumController::class, 'show'])->name('albums.show');
    Route::get('/search', SearchController::class)->name('search');

    // JSON для ленты — в группе web: та же сессия и тот же вход, что у страниц.
    Route::get('/api/timeline/{month}', TimelineMonthController::class)
        ->where('month', '\d{4}-\d{2}')
        ->name('api.timeline.month');

    Route::middleware('can:family')->group(function () {
        Route::put('/photos/favorite', [PhotoController::class, 'favoriteMany'])->name('photos.favorite.many');
        Route::put('/photos/{photo}/favorite', [PhotoController::class, 'favorite'])->whereNumber('photo')->name('photos.favorite');
    });

    Route::middleware('can:admin')->group(function () {
        Route::get('settings/users', [UserController::class, 'index'])->name('users.index');
        Route::post('settings/users', [UserController::class, 'store'])->name('users.store');
        Route::patch('settings/users/{user}', [UserController::class, 'update'])->name('users.update');
        Route::delete('settings/users/{user}', [UserController::class, 'destroy'])->name('users.destroy');
    });
});

require __DIR__.'/settings.php';
