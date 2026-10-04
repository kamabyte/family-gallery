<?php

use App\Http\Controllers\AlbumController;
use App\Http\Controllers\PhotoController;
use App\Http\Controllers\SearchController;
use App\Http\Controllers\TimelineController;
use Illuminate\Support\Facades\Route;

Route::get('/', TimelineController::class)->name('timeline');
Route::get('/photos/{photo}', [PhotoController::class, 'show'])->whereNumber('photo')->name('photos.show');
Route::put('/photos/favorite', [PhotoController::class, 'favoriteMany'])->name('photos.favorite.many');
Route::put('/photos/{photo}/favorite', [PhotoController::class, 'favorite'])->whereNumber('photo')->name('photos.favorite');
Route::get('/albums', [AlbumController::class, 'index'])->name('albums.index');
Route::get('/albums/{album}', [AlbumController::class, 'show'])->name('albums.show');
Route::get('/search', SearchController::class)->name('search');
