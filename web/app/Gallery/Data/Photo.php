<?php

namespace App\Gallery\Data;

use Carbon\CarbonImmutable;

/**
 * One catalog item — a row of the indexer's `photos` table (workers/indexer.py,
 * schema v4) plus the URLs the browser loads its derivatives from.
 */
final readonly class Photo
{
    public function __construct(
        public int $id,
        public string $filename,
        public bool $isVideo,
        public CarbonImmutable $takenAt,
        public int $width,
        public int $height,
        public ?int $durationSeconds,
        public ?string $city,
        public ?string $country,
        public ?float $lat,
        public ?float $lon,
        public ?string $cameraMake,
        public ?string $cameraModel,
        public bool $favorite,
        /** Dominant colour, shown while the thumbnail loads. */
        public string $color,
        public string $thumbUrl,
        public string $previewUrl,
        public ?string $videoUrl,
    ) {}

    public function date(): string
    {
        return $this->takenAt->format('Y-m-d');
    }

    public function month(): string
    {
        return $this->takenAt->format('Y-m');
    }

    public function camera(): ?string
    {
        if ($this->cameraModel === null) {
            return null;
        }

        // "Apple" + "iPhone 15 Pro" reads better than the raw EXIF pair.
        return $this->cameraMake !== null && ! str_starts_with($this->cameraModel, $this->cameraMake)
            ? "{$this->cameraMake} {$this->cameraModel}"
            : $this->cameraModel;
    }

    public function withFavorite(bool $favorite): self
    {
        $values = get_object_vars($this);
        $values['favorite'] = $favorite;

        return new self(...$values);
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return [
            'id' => $this->id,
            'type' => $this->isVideo ? 'video' : 'photo',
            'filename' => $this->filename,
            'taken_at' => $this->takenAt->toIso8601String(),
            'date' => $this->date(),
            'width' => $this->width,
            'height' => $this->height,
            'duration' => $this->durationSeconds,
            'favorite' => $this->favorite,
            'color' => $this->color,
            'thumb' => $this->thumbUrl,
            'preview' => $this->previewUrl,
            'video' => $this->videoUrl,
            'place' => $this->city !== null ? ['city' => $this->city, 'country' => $this->country] : null,
            'camera' => $this->camera(),
            'gps' => $this->lat !== null ? ['lat' => $this->lat, 'lon' => $this->lon] : null,
        ];
    }
}
