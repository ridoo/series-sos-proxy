/*
 * Copyright (C) 2013-2016 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *     - Apache License, version 2.0
 *     - Apache Software License, version 1.0
 *     - GNU Lesser General Public License, version 3
 *     - Mozilla Public License, versions 1.0, 1.1 and 2.0
 *     - Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public License
 * version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 */
package org.n52.series.db_custom.da;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.hibernate.Session;
import org.n52.io.DatasetFactoryException;
import org.n52.io.request.FilterResolver;
import org.n52.io.request.IoParameters;
import org.n52.io.request.Parameters;
import org.n52.io.response.PlatformOutput;
import org.n52.io.response.PlatformType;
import org.n52.io.response.dataset.AbstractValue;
import org.n52.io.response.dataset.Data;
import org.n52.io.response.dataset.DatasetOutput;
import org.n52.series.db.DataAccessException;
import org.n52.series.db_custom.SessionAwareRepository;
import org.n52.series.db_custom.beans.DatasetTEntity;
import org.n52.series.db_custom.beans.FeatureTEntity;
import org.n52.series.db_custom.beans.PlatformTEntity;
import org.n52.series.db_custom.dao.DbQuery;
import org.n52.series.db_custom.dao.FeatureDao;
import org.n52.series.db_custom.dao.PlatformDao;
import org.n52.series.spi.search.PlatformSearchResult;
import org.n52.series.spi.search.SearchResult;
import org.n52.web.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.vividsolutions.jts.geom.Geometry;
import org.n52.series.db.beans.DescribableEntity;

/**
 * TODO: JavaDoc
 *
 * @author <a href="mailto:h.bredel@52north.org">Henning Bredel</a>
 */
public class PlatformRepository extends SessionAwareRepository implements OutputAssembler<PlatformOutput> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlatformRepository.class);

    @Autowired
    private DatasetRepository<Data> seriesRepository;

    @Autowired
    private DataRepositoryFactory factory;

    @Override
    public boolean exists(String id, DbQuery parameters) throws DataAccessException {
        Session session = getSession();
        try {
            Long parsedId = parseId(PlatformType.extractId(id));
            if (PlatformType.isStationaryId(id)) {
                FeatureDao featureDao = new FeatureDao(session);
                return featureDao.hasInstance(parsedId, parameters, FeatureTEntity.class);
            } else {
                PlatformDao dao = new PlatformDao(session);
                return dao.hasInstance(parsedId, parameters, PlatformTEntity.class);
            }
        } finally {
            returnSession(session);
        }
    }

    @Override
    public List<PlatformOutput> getAllCondensed(DbQuery parameters) throws DataAccessException {
        Session session = getSession();
        try {
            List<PlatformOutput> results = new ArrayList<>();
            for (PlatformTEntity entity : getAllInstances(parameters, session)) {
                final PlatformOutput result = createCondensed(entity, parameters);
                results.add(result);
            }
            return results;
        } finally {
            returnSession(session);
        }
    }

    private PlatformOutput createCondensed(PlatformTEntity entity, DbQuery parameters) {
        PlatformOutput result = new PlatformOutput(entity.getPlatformType());
        result.setLabel(entity.getLabelFrom(parameters.getLocale()));
        result.setId(Long.toString(entity.getPkid()));
        result.setDomainId(entity.getDomainId());
        result.setHrefBase(urHelper.getPlatformsHrefBaseUrl(parameters.getHrefBase()));
        return result;
    }

    @Override
    public PlatformOutput getInstance(String id, DbQuery parameters) throws DataAccessException {
        Session session = getSession();
        try {
            if (PlatformType.isStationaryId(id)) {
                PlatformTEntity platform = getStation(id, parameters, session);
                return createExpanded(platform, parameters, session);
            } else {
                PlatformTEntity platform = getPlatform(id, parameters, session);
                return createExpanded(platform, parameters, session);
            }
        } finally {
            returnSession(session);
        }
    }

    @Override
    public List<PlatformOutput> getAllExpanded(DbQuery parameters) throws DataAccessException {
        Session session = getSession();
        try {
            List<PlatformOutput> results = new ArrayList<>();
            for (PlatformTEntity entity : getAllInstances(parameters, session)) {
                results.add(createExpanded(entity, parameters, session));
            }
            return results;
        } finally {
            returnSession(session);
        }
    }

    private PlatformOutput createExpanded(PlatformTEntity entity, DbQuery parameters, Session session) throws DataAccessException {
        PlatformOutput result = createCondensed(entity, parameters);
        DbQuery query = DbQuery.createFrom(parameters.getParameters()
                .extendWith(Parameters.PLATFORMS, result.getId())
                .removeAllOf(Parameters.FILTER_PLATFORM_TYPES));

        List<DatasetOutput> datasets = seriesRepository.getAllCondensed(query);
        result.setDatasets(datasets);

        Geometry geometry = entity.getGeometry();
        result.setGeometry(geometry == null
                ? getLastSamplingGeometry(datasets, query, session)
                : geometry);
        return result;
    }

    private Geometry getLastSamplingGeometry(List<DatasetOutput> datasets, DbQuery query, Session session) throws DataAccessException {
        AbstractValue<?> currentLastValue = null;
        for (DatasetOutput dataset : datasets) {
            // XXX fix generics and inheritance of Data, AbstractValue, etc.
            // https://trello.com/c/dMVa0fg9/78-refactor-data-abstractvalue
            try {
                String id = dataset.getId();
                DataRepository dataRepository = factory.create(dataset.getDatasetType());
                DatasetTEntity entity = seriesRepository.getInstanceEntity(id, query, session);
                AbstractValue<?> valueToCheck = dataRepository.getLastValue(entity, session, query);
                currentLastValue = getLaterValue(currentLastValue, valueToCheck);
            } catch (DatasetFactoryException e) {
                LOGGER.error("Couldn't create data repository to determing last value of dataset '{}'", dataset.getId());
            }
        }

        return currentLastValue != null && currentLastValue.isSetGeometry()
                ? currentLastValue.getGeometry()
                : null;
    }

    private AbstractValue< ? > getLaterValue(AbstractValue< ? > currentLastValue, AbstractValue< ? > valueToCheck) {
        if (currentLastValue == null) {
            return valueToCheck;
        }
        if (valueToCheck == null) {
            return currentLastValue;
        }
        return currentLastValue.getTimestamp() > valueToCheck.getTimestamp()
                ? currentLastValue
                : valueToCheck;
    }

    private PlatformTEntity getStation(String id, DbQuery parameters, Session session) throws DataAccessException {
        String featureId = PlatformType.extractId(id);
        FeatureDao featureDao = new FeatureDao(session);
        FeatureTEntity feature = featureDao.getInstance(Long.parseLong(featureId), parameters);
        if (feature == null) {
            throw new ResourceNotFoundException("Resource with id '" + id + "' could not be found.");
        }
        return PlatformType.isInsitu(id)
                ? convertInsitu(feature)
                : convertRemote(feature);
    }

    private PlatformTEntity getPlatform(String id, DbQuery parameters, Session session) throws DataAccessException {
        PlatformDao dao = new PlatformDao(session);
        String platformId = PlatformType.extractId(id);
        PlatformTEntity result = dao.getInstance(Long.parseLong(platformId), parameters);
        if (result == null) {
            throw new ResourceNotFoundException("Resource with id '" + id + "' could not be found.");
        }
        return result;
    }

    @Override
    public Collection<SearchResult> searchFor(IoParameters parameters) {
        Session session = getSession();
        try {
            PlatformDao dao = new PlatformDao(session);
            DbQuery query = getDbQuery(parameters);
            List<PlatformTEntity> found = dao.find(query);
            return convertToSearchResults(found, query);
        } finally {
            returnSession(session);
        }
    }

    @Override
    public List<SearchResult> convertToSearchResults(List<? extends DescribableEntity> found, DbQuery query) {
        List<SearchResult> results = new ArrayList<>();
        String locale = query.getLocale();
        for (DescribableEntity searchResult : found) {
            String pkid = searchResult.getPkid().toString();
            String label = searchResult.getLabelFrom(locale);
            String hrefBase = urHelper.getPlatformsHrefBaseUrl(query.getHrefBase());
            results.add(new PlatformSearchResult(pkid, label, hrefBase));
        }
        return results;
    }

    private List<PlatformTEntity> getAllInstances(DbQuery query, Session session) throws DataAccessException {
        List<PlatformTEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeStationaryPlatformTypes()) {
            platforms.addAll(getAllStationary(query, session));
        }
        if (filterResolver.shallIncludeMobilePlatformTypes()) {
            platforms.addAll(getAllMobile(query, session));
        }
        return platforms;
    }

    private List<PlatformTEntity> getAllStationary(DbQuery query, Session session) throws DataAccessException {
        List<PlatformTEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeInsituPlatformTypes()) {
            platforms.addAll(getAllStationaryInsitu(query, session));
        }
        if (filterResolver.shallIncludeRemotePlatformTypes()) {
            platforms.addAll(getAllStationaryRemote(query, session));
        }
        return platforms;
    }

    private List<PlatformTEntity> getAllStationaryInsitu(DbQuery parameters, Session session) throws DataAccessException {
        FeatureDao featureDao = new FeatureDao(session);
        DbQuery query = DbQuery.createFrom(parameters.getParameters()
                .removeAllOf(Parameters.FILTER_PLATFORM_TYPES)
                .extendWith(Parameters.FILTER_PLATFORM_TYPES, "stationary", "insitu"));
        return convertAllInsitu(featureDao.getAllInstances(query));
    }

    private List<PlatformTEntity> getAllStationaryRemote(DbQuery parameters, Session session) throws DataAccessException {
        FeatureDao featureDao = new FeatureDao(session);
        DbQuery query = DbQuery.createFrom(parameters.getParameters()
                .removeAllOf(Parameters.FILTER_PLATFORM_TYPES)
                .extendWith(Parameters.FILTER_PLATFORM_TYPES, "stationary", "remote"));
        return convertAllRemote(featureDao.getAllInstances(query));
    }

    private List<PlatformTEntity> getAllMobile(DbQuery query, Session session) throws DataAccessException {
        List<PlatformTEntity> platforms = new ArrayList<>();
        FilterResolver filterResolver = query.getFilterResolver();
        if (filterResolver.shallIncludeInsituPlatformTypes()) {
            platforms.addAll(getAllMobileInsitu(query, session));
        }
        if (filterResolver.shallIncludeRemotePlatformTypes()) {
            platforms.addAll(getAllMobileRemote(query, session));
        }
        return platforms;
    }

    private List<PlatformTEntity> getAllMobileInsitu(DbQuery parameters, Session session) throws DataAccessException {
        DbQuery query = DbQuery.createFrom(parameters.getParameters()
                .removeAllOf(Parameters.FILTER_PLATFORM_TYPES)
                .extendWith(Parameters.FILTER_PLATFORM_TYPES, "mobile", "insitu"));
        PlatformDao dao = new PlatformDao(session);
        return dao.getAllInstances(query);
    }

    private List<PlatformTEntity> getAllMobileRemote(DbQuery parameters, Session session) throws DataAccessException {
        DbQuery query = DbQuery.createFrom(parameters.getParameters()
                .removeAllOf(Parameters.FILTER_PLATFORM_TYPES)
                .extendWith(Parameters.FILTER_PLATFORM_TYPES, "mobile", "remote"));
        PlatformDao dao = new PlatformDao(session);
        return dao.getAllInstances(query);
    }

    private List<PlatformTEntity> convertAllInsitu(List<FeatureTEntity> entities) {
        List<PlatformTEntity> converted = new ArrayList<>();
        for (FeatureTEntity entity : entities) {
            converted.add(convertInsitu(entity));
        }
        return converted;
    }

    private List<PlatformTEntity> convertAllRemote(List<FeatureTEntity> entities) {
        List<PlatformTEntity> converted = new ArrayList<>();
        for (FeatureTEntity entity : entities) {
            converted.add(convertRemote(entity));
        }
        return converted;
    }

    private PlatformTEntity convertInsitu(FeatureTEntity entity) {
        PlatformTEntity platform = convertToPlatform(entity);
        platform.setInsitu(true);
        return platform;
    }

    private PlatformTEntity convertRemote(FeatureTEntity entity) {
        PlatformTEntity platform = convertToPlatform(entity);
        platform.setInsitu(false);
        return platform;
    }

    private PlatformTEntity convertToPlatform(FeatureTEntity entity) {
        PlatformTEntity result = new PlatformTEntity();
        result.setDomainId(entity.getDomainId());
        result.setPkid(entity.getPkid());
        result.setName(entity.getName());
        result.setTranslations(entity.getTranslations());
        result.setDescription(entity.getDescription());
        result.setGeometry(entity.getGeometry().getGeometry());
        return result;
    }

}
