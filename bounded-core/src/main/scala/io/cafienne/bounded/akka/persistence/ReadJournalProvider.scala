// Copyright (C) 2018 the original author or authors.
// See the LICENCE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package bounded.akka.persistence

import akka.actor.Props
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.journal.leveldb.{
  SharedLeveldbJournal,
  SharedLeveldbStore
}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.journal.leveldb.scaladsl.LeveldbReadJournal
import akka.persistence.query.scaladsl._
import bounded.akka.ActorSystemProvider
import bounded.akka.persistence.leveldb.SharedJournal

/**
  * Provides a readJournal that has the eventsByTag available that's used for
  * creation of the domain/query models of the system.
  */
trait ReadJournalProvider { systemProvider: ActorSystemProvider =>

  val configuredJournal =
    system.settings.config.getString("akka.persistence.journal.plugin")

  def readJournal
    : ReadJournal with CurrentEventsByTagQuery with EventsByTagQuery with CurrentEventsByPersistenceIdQuery = {
    system.log.debug("found configured journal " + configuredJournal)
    if (configuredJournal.endsWith("leveldb")) {
      system.log.debug("configuring read journal for leveldb")
      return PersistenceQuery(system)
        .readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
    }
    if (configuredJournal.endsWith("leveldb-shared")) {
      system.log.debug("configuring read journal for leveldb-shared")

      val sharedJournal =
        system.actorOf(Props(new SharedLeveldbStore), SharedJournal.name)
      SharedLeveldbJournal.setStore(sharedJournal, system)

      return PersistenceQuery(system)
        .readJournalFor[LeveldbReadJournal](LeveldbReadJournal.Identifier)
    }
    if (configuredJournal.endsWith("cassandra-journal")) {
      system.log.debug("configuring read journal for cassandra")
      return PersistenceQuery(system)
        .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)
    }
    if (configuredJournal.endsWith("inmemory-journal")) {
      return PersistenceQuery(system)
        .readJournalFor("inmemory-read-journal")
        .asInstanceOf[ReadJournal with CurrentPersistenceIdsQuery with CurrentEventsByPersistenceIdQuery with CurrentEventsByTagQuery with EventsByPersistenceIdQuery with EventsByTagQuery]
    }
    throw new RuntimeException(
      s"Unsupported read journal $configuredJournal, please switch to cassandra for production")
  }
}