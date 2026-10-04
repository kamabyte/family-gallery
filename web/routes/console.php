<?php

use App\Enums\Role;
use App\Models\User;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Str;

use function Laravel\Prompts\password;

Artisan::command('gallery:user {email} {--name=} {--password=} {--role= : admin, member или guest}', function (string $email, ?string $name = null, ?string $password = null, ?string $role = null) {
    $email = strtolower($email);

    if ($role !== null && Role::tryFrom($role) === null) {
        $this->error('--role: admin, member или guest');

        return 1;
    }

    $user = User::firstOrNew(['email' => $email]);
    // существующему только роль — пароль не трогаем
    $keepPassword = $user->exists && $password === null && $role !== null;
    $pw = $keepPassword ? null : ($password ?: ($this->input->isInteractive() ? password('Пароль (пусто — сгенерировать)') : '') ?: Str::password(16, symbols: false));

    // без --role: у существующего роль не меняется, новый — администратор
    // (так заводят первую учётную запись), остальных удобнее заводить в интерфейсе
    $user->fill([
        'name' => $name ?: ($user->name ?? Str::before($email, '@')),
        'role' => $role ?? ($user->exists ? $user->role : Role::Admin),
    ]);
    if ($pw !== null) {
        $user->password = Hash::make($pw);
    }
    $user->forceFill(['email_verified_at' => $user->email_verified_at ?? now()])->save();

    $this->info("учётная запись {$email} ({$user->role->label()}) готова".($pw !== null && ! $password ? ", пароль: {$pw}" : ''));
})->purpose('Создать учётную запись галереи, сменить ей пароль или роль');
