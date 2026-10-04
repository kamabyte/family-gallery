<?php

namespace App\Gallery;

use App\Models\Favorite;
use App\Models\User;
use Closure;

/**
 * Избранное того, кто смотрит. Пользователь узнаётся при каждом обращении, а не
 * при создании: один экземпляр не должен унести избранное одного человека к
 * другому (долгоживущий процесс, тесты с несколькими входами). Без входа —
 * пусто и только для чтения.
 */
final class Favorites
{
    /** @var array<int, true> */
    private array $ids = [];

    private ?int $loadedFor = null;

    /** @param  Closure(): ?User  $user */
    public function __construct(private readonly Closure $user) {}

    public static function none(): self
    {
        return new self(fn () => null);
    }

    /** Чьё избранное сейчас — чтобы кеши поверх него знали, когда устарели. */
    public function owner(): ?int
    {
        return ($this->user)()?->id;
    }

    /** @return array<int, true> */
    public function ids(): array
    {
        $user = ($this->user)();
        if ($user === null) {
            return [];
        }
        if ($this->loadedFor !== $user->id) {
            $this->ids = array_fill_keys($user->favorites()->pluck('photo_id')->all(), true);
            $this->loadedFor = $user->id;
        }

        return $this->ids;
    }

    public function set(int $photoId, bool $favorite): void
    {
        $user = ($this->user)();
        if ($user === null) {
            return;
        }

        if ($favorite) {
            Favorite::firstOrCreate(['user_id' => $user->id, 'photo_id' => $photoId]);
        } else {
            Favorite::where(['user_id' => $user->id, 'photo_id' => $photoId])->delete();
        }

        $this->loadedFor = null;
    }
}
