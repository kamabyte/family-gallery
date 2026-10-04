<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Избранное — у каждого своё. photo_id — id кадра в каталоге галереи: в каталоге
 * индексатора избранного нет, поэтому оно живёт здесь.
 *
 * @property int $user_id
 * @property int $photo_id
 */
#[Fillable(['user_id', 'photo_id'])]
class Favorite extends Model
{
    /** @return BelongsTo<User, $this> */
    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
