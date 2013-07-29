package com.foursquare.fongo.impl;

import com.mongodb.DBObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An index for the MongoDB.
 * Must be immutable.
 */
public class Index {
  private final String name;
  private final List<String> fields;
  private final boolean unique;
  private final ExpressionParser expressionParser = new ExpressionParser();

  // Contains all dbObject than field value can have.
  private final ConcurrentHashMap<List<List<Object>>, List<DBObject>> mapValues = new ConcurrentHashMap<List<List<Object>>, List<DBObject>>();

  private int usedTime = 0;

  public Index(String name, List<String> fields, boolean unique) {
    this.name = name;
    this.fields = fields;
    this.unique = unique;
  }

  public Index(String name, DBObject keys, boolean unique) {
    this(name, new ArrayList<String>(keys.keySet()), unique);
  }

  public String getName() {
    return name;
  }

  public boolean isUnique() {
    return unique;
  }

  public List<String> getFields() {
    return fields;
  }

  /**
   * @param object    new object to insert in the index.
   * @param oldObject in update, old objet to remove from index.
   * @return keys in error if uniqueness is not respected, empty collection otherwise.
   */
  public synchronized List<List<Object>> addOrUpdate(DBObject object, DBObject oldObject) {
    if (oldObject != null) {
      this.remove(oldObject); // TODO : optim ?
    }
    List<List<Object>> fieldsForIndex = IndexUtil.INSTANCE.extractFields(object, getFields());
//    DBObject id = new BasicDBObject(FongoDBCollection.ID_KEY, object.get(FongoDBCollection.ID_KEY));
    if (unique) {
      // Return null only if key was absent.
      if (mapValues.putIfAbsent(fieldsForIndex, Collections.singletonList(object)) != null) {
        return fieldsForIndex;
      }
    } else {
      // Extract previous values
      List<DBObject> values = mapValues.get(fieldsForIndex);
      if (values == null) {
        // Create if absent.
        values = new ArrayList<DBObject>();
        mapValues.put(fieldsForIndex, values);
      }

      // Add to values.
      values.add(object);
    }
    return Collections.emptyList();
  }

  /**
   * Check, in case of unique index, if we can add it.
   *
   * @param object
   * @param oldObject old object if update, null elsewhere.
   * @return keys in error if uniqueness is not respected, empty collection otherwise.
   */
  public synchronized List<List<Object>> checkAddOrUpdate(DBObject object, DBObject oldObject) {
    if (unique) {
      List<List<Object>> fieldsForIndex = IndexUtil.INSTANCE.extractFields(object, getFields());
      List<DBObject> objects = mapValues.get(fieldsForIndex);
      if (objects != null && !objects.contains(oldObject)) {
        return fieldsForIndex;
      }
    }
    return Collections.emptyList();
  }

  /**
   * Remove an object from the index.
   *
   * @param object to remove from the index.
   */
  public synchronized void remove(DBObject object) {
    List<List<Object>> fieldsForIndex = IndexUtil.INSTANCE.extractFields(object, getFields());
//    if (unique) {
//      mapValues.remove(fieldsForIndex);
//    } else {
    // Extract previous values
    List<DBObject> values = mapValues.get(fieldsForIndex);
    if (values != null) {
      // Last entry ? or uniqueness ?
      if (values.size() == 1) {
        mapValues.remove(fieldsForIndex);
      } else {
        values.remove(object);
      }
//      }
    }
  }

  /**
   * Multiple add of objects.
   *
   * @param objects to add.
   * @return keys in error if uniqueness is not respected, empty collection otherwise.
   */
  public synchronized List<List<Object>> addAll(Iterable<DBObject> objects) {
    for (DBObject object : objects) {
      List<List<Object>> nonUnique = addOrUpdate(object, null);
      if (!nonUnique.isEmpty()) {
        return nonUnique;
      }
    }
    return Collections.emptyList();
  }

  public List<DBObject> get(DBObject query) {
    List<List<Object>> fieldsForIndex = IndexUtil.INSTANCE.extractFields(query, getFields());
    return mapValues.get(fieldsForIndex);
  }

  public synchronized Collection<DBObject> retrieveObjects(DBObject query) {
    usedTime++;
    Filter filter = expressionParser.buildFilter(query);
    List<DBObject> result = new ArrayList<DBObject>();
    for (Map.Entry<List<List<Object>>, List<DBObject>> entry : mapValues.entrySet()) {
      for(DBObject object : entry.getValue()) {
        if(filter.apply(object)) {
          result.add(object);
        }
      }
    }
//    List<List<Object>> queryValues = IndexUtil.INSTANCE.extractFields(query, fields);
//    List<DBObject> objects = mapValues.get(queryValues);
//    return objects != null ? objects : Collections.<DBObject>emptyList();
    return result;
  }

  public long getUsedTime() {
    return usedTime;
  }

  @Override
  public String toString() {
    return "Index{" +
        "name='" + name + '\'' +
        '}';
  }

  public synchronized int size() {
    return mapValues.size();
  }

  public synchronized List<DBObject> values() {
    List<DBObject> values = new ArrayList<DBObject>(mapValues.size() * 10);
    for (List<DBObject> objects : mapValues.values()) {
      values.addAll(objects);
    }
    return values;
  }

  public void clear() {
    mapValues.clear();
  }
}
