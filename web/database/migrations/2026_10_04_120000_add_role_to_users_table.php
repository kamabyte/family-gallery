<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // по умолчанию — наименьшие права; первую учётную запись заводит `php artisan gallery:user`
        Schema::table('users', function (Blueprint $table) {
            $table->string('role')->default('guest')->after('email');
        });
    }

    public function down(): void
    {
        Schema::table('users', fn (Blueprint $table) => $table->dropColumn('role'));
    }
};
