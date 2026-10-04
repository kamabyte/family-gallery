<?php

use App\Http\Controllers\Api\TimelineMonthController;
use Illuminate\Support\Facades\Route;

// The fake API of the prototype: same shapes the real catalog will serve.
Route::get('/timeline/{month}', TimelineMonthController::class)
    ->where('month', '\d{4}-\d{2}')
    ->name('api.timeline.month');
