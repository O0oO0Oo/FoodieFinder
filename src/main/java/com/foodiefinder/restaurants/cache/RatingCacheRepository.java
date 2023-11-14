package com.foodiefinder.restaurants.cache;

import com.foodiefinder.common.cache.CacheUtils;
import com.foodiefinder.common.enums.CacheKeyPrefix;
import com.foodiefinder.datapipeline.writer.dto.RestaurantCacheDto;
import com.foodiefinder.datapipeline.writer.entity.Restaurant;
import com.foodiefinder.restaurants.entity.Rating;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RatingCacheRepository {
    private final CacheUtils cacheUtils;

    /**
     * 유저가 평가할떄 redis 와 동기화 하기 위한 메서드
     */
    public void modifyRatingAtRestaurantCache(Restaurant restaurant) {
        log.info("id {} 에 대한 캐시 동기화",restaurant.getId());

        Double latitude = restaurant.getLatitude();
        Double longitude = restaurant.getLongitude();

        List<Rating> ratings = restaurant.getRatings();
        int count = ratings.size();
        Double sumRatings = Double.valueOf(ratings.stream()
                .reduce(0, (total, rating) -> total + rating.getValue(), Integer::sum));

        RestaurantCacheDto restaurantCacheDto = RestaurantCacheDto.setCache(restaurant, sumRatings / count, count);

        try (RedisConnection connection = cacheUtils.getConnection()) {
            GeoResults<RedisGeoCommands.GeoLocation<byte[]>> geoResults = connection.geoCommands()
                    .geoRadius(
                            (CacheKeyPrefix.MAP_SGG.getKeyPrefix() + restaurantCacheDto.getSigunName()).getBytes(),
                            cacheUtils.getCircle(latitude, longitude, 0.01, Metrics.KILOMETERS)
                    );


            if(!geoResults.getContent().isEmpty()) {

                geoResults.getContent()
                    .stream()
                        .filter(Objects::nonNull)
                        .filter(data -> Long.valueOf(cacheUtils.decodeFromByteArray(data.getContent().getName()).split(":")[0]).equals(restaurant.getId()))
                        .forEach(data -> {
                            connection.zSetCommands()
                                    .zRem(
                                            (CacheKeyPrefix.MAP_SGG.getKeyPrefix() + restaurantCacheDto.getSigunName()).getBytes(),
                                            data.getContent().getName()
                                    );

                            connection.geoCommands()
                                    .geoAdd(
                                            (CacheKeyPrefix.MAP_SGG.getKeyPrefix() + restaurantCacheDto.getSigunName()).getBytes(),
                                            new Point(longitude, latitude),
                                            (restaurantCacheDto.toString()).getBytes()
                                    );
                        });
                return;
            }
            connection.geoCommands()
                    .geoAdd(
                            (CacheKeyPrefix.MAP_SGG.getKeyPrefix() + restaurantCacheDto.getSigunName()).getBytes(),
                            new Point(longitude, latitude),
                            (restaurantCacheDto.toString()).getBytes()
                    );
        }
    }
}
