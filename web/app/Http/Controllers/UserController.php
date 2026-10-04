<?php

namespace App\Http\Controllers;

use App\Enums\Role;
use App\Models\User;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Illuminate\Validation\Rule;
use Inertia\Inertia;
use Inertia\Response;

/**
 * Учётные записи и их роли — как в awgkeys. Регистрации нет: заводит администратор
 * (здесь или `php artisan gallery:user`). Почты нет, поэтому новый пароль выдаётся
 * здесь и показывается один раз.
 */
class UserController extends Controller
{
    public function index(Request $request): Response
    {
        return Inertia::render('settings/users', [
            'users' => User::withCount('favorites')->orderBy('name')->get()->map(fn (User $u) => [
                'id' => $u->id,
                'name' => $u->name,
                'email' => $u->email,
                'role' => $u->role->value,
                'two_factor' => $u->two_factor_confirmed_at !== null,
                'favorites' => $u->favorites_count,
                'created_at' => $u->created_at?->toIso8601String(),
                'is_me' => $u->is($request->user()),
            ])->all(),
            'roles' => collect(Role::cases())->map(fn (Role $r) => [
                'value' => $r->value,
                'label' => $r->label(),
                'description' => $r->description(),
            ])->all(),
        ]);
    }

    public function store(Request $request): RedirectResponse
    {
        $v = $request->validate([
            'name' => ['nullable', 'string', 'max:255'],
            'email' => ['required', 'email', 'max:255', Rule::unique('users', 'email')],
            'role' => ['required', Rule::enum(Role::class)],
            'password' => ['nullable', 'string', 'min:8', 'max:255'],
        ], [], ['email' => 'email', 'role' => 'роль', 'password' => 'пароль']);

        $email = mb_strtolower($v['email']);
        $password = filled($v['password'] ?? null) ? $v['password'] : Str::password(16, symbols: false);

        $user = User::create([
            'name' => filled($v['name'] ?? null) ? $v['name'] : Str::before($email, '@'),
            'email' => $email,
            'role' => $v['role'],
            'password' => $password,
        ]);
        // адрес заводит администратор — подтверждать его письмом незачем
        $user->forceFill(['email_verified_at' => now()])->save();

        Inertia::flash('toast', ['type' => 'success', 'message' => "Учётная запись {$email} заведена"]);
        if (blank($v['password'] ?? null)) {
            // показывается один раз: пароль нигде не хранится в открытом виде
            Inertia::flash('password', ['email' => $email, 'password' => $password]);
        }

        return back();
    }

    /**
     * Сменить имя, роль или выдать новый пароль.
     */
    public function update(Request $request, User $user): RedirectResponse
    {
        $v = $request->validate([
            'name' => ['sometimes', 'required', 'string', 'max:255'],
            'role' => ['sometimes', 'required', Rule::enum(Role::class)],
            'reset_password' => ['boolean'],
        ]);

        if (isset($v['role']) && $v['role'] !== Role::Admin->value && $user->isAdmin() && self::lastAdmin($user)) {
            return back()->withErrors(['role' => 'Это последний администратор — сначала назначьте другого.']);
        }

        $user->fill(array_intersect_key($v, array_flip(['name', 'role'])));

        $password = null;
        if ($request->boolean('reset_password')) {
            $password = Str::password(16, symbols: false);
            $user->password = $password;
        }

        $user->save();

        if ($password !== null) {
            // выкинуть из всех сессий: старый пароль больше не действует
            DB::table('sessions')->where('user_id', $user->id)->delete();
            Inertia::flash('password', ['email' => $user->email, 'password' => $password]);
        }
        Inertia::flash('toast', ['type' => 'success', 'message' => "Учётная запись {$user->email} обновлена"]);

        return back();
    }

    public function destroy(Request $request, User $user): RedirectResponse
    {
        if ($user->is($request->user())) {
            return back()->withErrors(['user' => 'Себя удалить отсюда нельзя — это делается в профиле.']);
        }
        if ($user->isAdmin() && self::lastAdmin($user)) {
            return back()->withErrors(['user' => 'Это последний администратор.']);
        }

        DB::table('sessions')->where('user_id', $user->id)->delete();
        $user->delete();

        Inertia::flash('toast', ['type' => 'success', 'message' => "Учётная запись {$user->email} удалена"]);

        return back();
    }

    private static function lastAdmin(User $user): bool
    {
        return ! User::where('role', Role::Admin)->whereKeyNot($user->id)->exists();
    }
}
