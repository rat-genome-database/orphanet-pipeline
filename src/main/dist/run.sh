#!/usr/bin/env bash
. /etc/profile

SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
APPNAME="orphanet-pipeline"
APPDIR=/home/rgddata/pipelines/$APPNAME
EMAILLIST=mtutaj@mcw.edu

if [ "$SERVER" == "REED" ]; then
  EMAILLIST="mtutaj@mcw.edu jrsmith@mcw.edu slaulede@mcw.edu"
fi

cd $APPDIR

java -Dspring.config=$APPDIR/../properties/default_db2.xml \
    -Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml \
    -jar lib/$APPNAME.jar "$@" 2>&1

mailx -s "[$SERVER] Orphanet Pipeline Summary" $EMAILLIST < $APPDIR/logs/summary.log
