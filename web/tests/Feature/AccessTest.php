<?php

use App\Enums\Role;
use App\Gallery\GalleryCatalog;
use App\Models\Favorite;
use App\Models\User;
use Illuminate\Support\Facades\Hash;
use Inertia\Testing\AssertableInertia as Assert;

it('sends signed-out visitors to the login page', function (string $url) {
    $this->get($url)->assertRedirect('/login');
})->with(['/', '/albums', '/photos/1', '/search', '/settings/profile', '/settings/users']);

it('answers the timeline API with 401 when signed out', function () {
    $this->getJson('/api/timeline/2025-01')->assertUnauthorized();
});

it('renders the login page in the auth layout', function () {
    $this->get('/login')->assertOk()->assertInertia(fn (Assert $page) => $page->component('auth/login'));
});

it('logs in with email and password and lands on the timeline', function () {
    $user = User::factory()->create(['password' => Hash::make('correct horse')]);

    $this->post('/login', ['email' => $user->email, 'password' => 'correct horse'])->assertRedirect('/');
    $this->assertAuthenticatedAs($user);
});

it('rejects a wrong password', function () {
    $user = User::factory()->create();

    $this->post('/login', ['email' => $user->email, 'password' => 'wrong'])->assertSessionHasErrors('email');
    $this->assertGuest();
});

it('has no self-registration and no password reset by email', function () {
    $this->get('/register')->assertNotFound();
    $this->get('/forgot-password')->assertNotFound();
});

it('shares the role and abilities with the pages', function (Role $role, bool $admin, bool $family) {
    $this->actingAs(User::factory()->create(['role' => $role]))
        ->get('/albums')
        ->assertInertia(fn (Assert $page) => $page
            ->where('auth.user.role', $role->value)
            ->where('auth.can.admin', $admin)
            ->where('auth.can.family', $family));
})->with([
    'admin' => [Role::Admin, true, true],
    'member' => [Role::Member, false, true],
    'guest' => [Role::Guest, false, false],
]);

it('lets a guest look but not mark favourites', function () {
    $this->actingAs(User::factory()->guest()->create());
    $id = app(GalleryCatalog::class)->buckets()[0]['month'];

    $this->get('/')->assertOk();
    $this->put('/photos/1/favorite', ['favorite' => true])->assertForbidden();
    $this->put('/photos/favorite', ['ids' => [1], 'favorite' => true])->assertForbidden();
    $this->get('/albums/favorites')->assertNotFound();
    $this->get('/albums')->assertInertia(fn (Assert $page) => $page
        ->where('albums', fn ($albums) => collect($albums)->doesntContain('id', 'favorites')));
    expect(Favorite::count())->toBe(0);
});

it('keeps each person their own favourites', function () {
    [$mom, $dad] = User::factory()->count(2)->create();

    $this->actingAs($mom)->put('/photos/7/favorite', ['favorite' => true]);

    $this->actingAs($dad)->get('/photos/7')->assertInertia(fn (Assert $page) => $page->where('photo.favorite', false));
    $this->actingAs($mom)->get('/photos/7')->assertInertia(fn (Assert $page) => $page->where('photo.favorite', true));
});

it('opens the users page only to admins', function () {
    $this->actingAs(User::factory()->create())->get('/settings/users')->assertForbidden();
    $this->actingAs(User::factory()->admin()->create())->get('/settings/users')
        ->assertOk()
        ->assertInertia(fn (Assert $page) => $page
            ->component('settings/users')
            ->has('roles', 3));
});

it('lets an admin create an account and shows a generated password once', function () {
    $this->actingAs(User::factory()->admin()->create());

    $this->post('/settings/users', ['email' => 'Babushka@Family.Home', 'role' => 'guest'])
        ->assertRedirect()
        ->assertInertiaFlash('password.email', 'babushka@family.home');

    $user = User::where('email', 'babushka@family.home')->sole();
    expect($user->role)->toBe(Role::Guest)
        ->and($user->name)->toBe('babushka');
});

it('changes roles and resets passwords', function () {
    $this->actingAs(User::factory()->admin()->create());
    $user = User::factory()->create(['password' => Hash::make('old password')]);

    $this->patch("/settings/users/{$user->id}", ['role' => 'admin'])->assertRedirect();
    expect($user->fresh()->role)->toBe(Role::Admin);

    $this->patch("/settings/users/{$user->id}", ['reset_password' => true])->assertInertiaFlash('password.email', $user->email);
    expect(Hash::check('old password', $user->fresh()->password))->toBeFalse();
});

it('never demotes or deletes the last admin', function () {
    $admin = User::factory()->admin()->create();
    $other = User::factory()->admin()->create();
    $this->actingAs($admin);

    $this->delete("/settings/users/{$other->id}")->assertRedirect();
    expect(User::find($other->id))->toBeNull();

    // Остался один администратор — он сам: понизить нельзя.
    $this->patch("/settings/users/{$admin->id}", ['role' => 'member'])->assertSessionHasErrors('role');
    expect($admin->fresh()->role)->toBe(Role::Admin);
});

it('creates the first admin from the console', function () {
    $this->artisan('gallery:user', ['email' => 'Lenar@Home', '--password' => 'secret-pass-123'])
        ->expectsOutputToContain('Администратор')
        ->assertSuccessful();

    $user = User::where('email', 'lenar@home')->sole();
    expect($user->isAdmin())->toBeTrue()
        ->and(Hash::check('secret-pass-123', $user->password))->toBeTrue();
});

it('changes only the role of an existing account from the console', function () {
    $user = User::factory()->create(['password' => Hash::make('keep me')]);

    $this->artisan('gallery:user', ['email' => $user->email, '--role' => 'guest'])->assertSuccessful();

    expect($user->fresh()->role)->toBe(Role::Guest)
        ->and(Hash::check('keep me', $user->fresh()->password))->toBeTrue();
});

it('asks for the password again before the security page', function () {
    $this->actingAs(User::factory()->create())->get('/settings/security')->assertRedirect('/user/confirm-password');
});
