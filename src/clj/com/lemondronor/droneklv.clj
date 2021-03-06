(ns com.lemondronor.droneklv
  "Work with KLV metadata frome drone video."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as string]
            [com.lemonodor.xio :as xio]
            [gloss.core :as gloss]
            [gloss.io])
  (:import [com.lemondronor.droneklv KLV KLV$KeyLength KLV$LengthEncoding]
           [java.util Arrays]))

(set! *warn-on-reflection* true)


(defn ints->bytes
  [ints]
  (mapv (fn [i]
          (let [i (int i)]
            (byte
             (cond (<= 0 i 127)
                   i
                   (<= 128 i 255)
                   (- i 256)
                   :else
                   (throw (IllegalArgumentException.
                           (format "Value out of range for byte: %s" i)))))))
        ints))


(defn bytes->ints
  [bytes]
  (mapv (fn [i]
          (if (not (<= -128 i 127))
            (throw (IllegalArgumentException.
                    (format "Value out of range for byte: %s" i)))
            (bit-and 0xff (int i))))
        bytes))


;; Taken from
;; http://trac.osgeo.org/ossim/browser/trunk/ossimPredator/src/ossimPredatorKlvTable.cpp

(def tags
  [[:klv-key-stream-id
    "stream ID",
    [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x01 0x03 0x04 0x02 0x00 0x00 0x00 0x00]],
   [:klv-key-organizational-program-number "Organizational Program Number",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x01 0x03 0x05 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-unix-timestamp "UNIX Timestamp",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x04 0x07 0x02 0x01 0x01 0x01 0x05 0x00 0x00]],
   [:klv-key-user-defined-utc-timestamp "User Defined UTC" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x02 0x01 0x01 0x01 0x01 0x00 0x00]],
   [:klv-key-user-defined-timestamp-microseconds-1970 "User Defined Timestamp Microseconds since 1970" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x02 0x01 0x01 0x01 0x05 0x00 0x00]],
   [:klv-key-video-start-date-time-utc "Video Timestamp Start Date and Time",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x02 0x01 0x02 0x01 0x01 0x00 0x00]],
   [:klv-timesystem-offset "Time System Offset From UTC" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x03 0x01 0x03 0x03 0x01 0x00 0x00 0x00]],
   [:klv-uas-datalink-local-dataset "UAS Datalink Local Data Set",[0x06 0x0E 0x2B 0x34 0x02 0x0B 0x01 0x01 0x0E 0x01 0x03 0x01 0x01 0x00 0x00 0x00]],
   [:klv-basic-universal-metadata-set "Universal Metadata Set",[0x06 0x0E 0x2B 0x34 0x02 0x01 0x01 0x01 0x0E 0x01 0x01 0x02 0x01 0x01 0x00 0x00]],
   [:klv-security-metadata-universal-set "Security metadata universal set" [0x06 0x0E 0x2B 0x34 0x02 0x01 0x01 0x01 0x02 0x08 0x02 0x00 0x00 0x00 0x00 0x00]],
   [:klv-url-string "URL String" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x01 0x02 0x01 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-security-classification-set "Security Classification Set" [0x06 0x0E 0x2B 0x34 0x02 0x01 0x01 0x01 0x02 0x08 0x02 0x00 0x00 0x00 0x00 0x00]],
   [:klv-key-byte-order "Byte Order" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x03 0x01 0x02 0x01 0x02 0x00 0x00 0x00]],
   [:klv-key-mission-number"Mission Number",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x01 0x05 0x05 0x00 0x00 0x00 0x00 0x00]],
   [:klv-key-object-country-codes "Object Country Codes" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x20 0x01 0x02 0x01 0x01 0x00]],
   [:klv-key-security-classification "Security Classification" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x02 0x08 0x02 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-security-release-instructions "Release Instructions" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x20 0x01 0x02 0x09 0x00 0x00]],
   [:klv-key-security-caveats "Caveats" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x02 0x08 0x02 0x02 0x00 0x00 0x00 0x00]],
   [:klv-key-classification-comment "Classification Comment" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x02 0x08 0x02 0x07 0x00 0x00 0x00 0x00]],
   [:klv-key-original-producer-name "Original Producer Name" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x02 0x01 0x03 0x00 0x00 0x00 0x00 0x00]],
   [:klv-key-platform-ground-speed"Platform Ground Speed",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x01 0x05 0x00 0x00 0x00]],
   [:klv-key-platform-magnetic-heading-angle"Platform Magnetic Heading Angle",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x01 0x08 0x00 0x00 0x00]],
   [:klv-key-platform-heading-angle"Platform Heading Angle",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x07 0x07 0x01 0x10 0x01 0x06 0x00 0x00 0x00]],
   [:klv-key-platform-pitch-angle"Platform Pitch Angle",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x07 0x07 0x01 0x10 0x01 0x05 0x00 0x00 0x00]],
   [:klv-key-platform-roll-angle "Platform Roll Angle",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x07 0x07 0x01 0x10 0x01 0x04 0x00 0x00 0x00]],
   [:klv-key-indicated-air-speed "Platform Indicated Air Speed",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x01 0x0B 0x00 0x00 0x00]],
   [:klv-key-platform-designation "Platform Designation",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x01 0x01 0x20 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-platform-designation2 "Platform Designation",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x01 0x01 0x21 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-image-source-sensor "Image Source Sensor",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x04 0x20 0x01 0x02 0x01 0x01 0x00 0x00]],
   [:klv-key-image-coordinate-system "Image Coordinate System",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x01 0x01 0x00 0x00 0x00 0x00]],
   [:klv-key-sensor-latitude "Sensor Latitude",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x02 0x04 0x02 0x00]],
   [:klv-key-sensor-longitude "Sensor Longitude",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x02 0x06 0x02 0x00]],
   [:klv-key-sensor-true-altitude "Sensor True Altitude",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x02 0x01 0x02 0x02 0x00 0x00]],
   [:klv-key-sensor-horizontal-fov "Sensor Horizontal Field Of View",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x02 0x04 0x20 0x02 0x01 0x01 0x08 0x00 0x00]],
   [:klv-key-sensor-vertical-fov1 "Sensor Vertical Field Of View",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x07 0x04 0x20 0x02 0x01 0x01 0x0A 0x01 0x00]],
   [:klv-key-sensor-vertical-fov2 "Sensor Vertical Field Of View",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x04 0x20 0x02 0x01 0x01 0x0A 0x01 0x00]],
   [:klv-key-slant-range "Slant Range",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x08 0x01 0x01 0x00 0x00 0x00]],
   [:klv-key-obliquity-angle "Obliquity Angle",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x10 0x01 0x03 0x00 0x00 0x00]],
   [:klv-key-angle-to-north "Angle To North" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x10 0x01 0x02 0x00 0x00 0x00]],
   [:klv-key-target-width "Target Width",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x09 0x02 0x01 0x00 0x00 0x00]],
   [:klv-key-frame-center-latitude "Frame Center Latitude",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x02 0x01 0x03 0x02 0x00 0x00]],
   [:klv-key-frame-center-longitude "Frame Center Longitude",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x02 0x01 0x03 0x04 0x00 0x00]],
   [:klv-key-frame-center-elevation "Frame Center elevation",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x06 0x07 0x01 0x02 0x03 0x10 0x00 0x00 0x00]],
   [:klv-key-corner-latitude-point-1 "Corner Latitude Point 1",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x07 0x01 0x00]],
   [:klv-key-corner-longitude-point-1 "Corner Longitude Point 1",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x0B 0x01 0x00]],
   [:klv-key-corner-latitude-point-2 "Corner Latitude Point 2",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x08 0x01 0x00]],
   [:klv-key-corner-longitude-point-2 "Corner Longitude Point 2",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x0C 0x01 0x00]],
   [:klv-key-corner-latitude-point-3 "Corner Latitude Point 3",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x09 0x01 0x00]],
   [:klv-key-corner-longitude-point-3 "Corner Longitude Point 3",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x0D 0x01 0x00]],
   [:klv-key-corner-latitude-point-4 "Corner Latitude Point 4",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x0A 0x01 0x00]],
   [:klv-key-corner-longitude-point-4 "Corner Longitude Point 4",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x03 0x07 0x01 0x02 0x01 0x03 0x0E 0x01 0x00]],
   [:klv-key-device-absolute-speed "Device Absolute Speed",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x03 0x01 0x01 0x01 0x00 0x00]],
   [:klv-key-device-absolute-heading "Device Absolute Heading",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x03 0x01 0x01 0x02 0x00 0x00]],
   [:klv-key-absolute-event-start-date "Absolute Event Start Date",[0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x02 0x01 0x02 0x07 0x01 0x00 0x00]],
   [:klv-key-sensor-roll-angle "Sensor Roll Angle" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x07 0x01 0x10 0x01 0x01 0x00 0x00 0x00]],
   [:klv-key-sensor-relative-elevation-angle "Sensor Relative Elevation Angle" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x02 0x05 0x00 0x00 0x00]],
   [:klv-key-sensor-relative-azimuth-angle "Sensor Relative Azimuth Angle" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x02 0x04 0x00 0x00 0x00]],
   [:klv-key-sensor-relative-roll-angle "Sensor Relative Roll Angle" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x02 0x06 0x00 0x00 0x00]],
   [:klv-key-uas-lds-version-number "UAS LDS Version Number" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x02 0x03 0x03 0x00 0x00 0x00]],
   [:klv-key-generic-flag-data-01 "Generic Flag Data 01" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x03 0x01 0x00 0x00 0x00]],
   [:klv-key-static-pressure "Static Pressure" [0x06 0x0E 0x2B 0x34 0x01 0x01 0x01 0x01 0x0E 0x01 0x01 0x01 0x0F 0x00 0x00 0x00]]])

(def tags-table
  (map (fn [[sym name key]]
         [sym name (byte-array (ints->bytes key))])
       tags))


(defn find-klv-signature [^bytes key]
  (loop [tags tags-table]
    (if-let [tag (first tags)]
      (if (Arrays/equals ^bytes (tag 2) key)
        tag
        (recur (rest tags)))
      nil)))


(defn scaler [src-min src-max dst-min dst-max]
  #(float
    (+ (* (/ (- % src-min)
             (- src-max src-min))
          (- dst-max dst-min))
       dst-min)))


(def lat-scaler (scaler -2147483647 2147483647 -90 90))
(def lon-scaler (scaler -2147483647 2147483647 -180 180))
(def pos-delta-scaler (scaler -32767 32767 -0.075 0.075))

(def local-set-tags
  (into
   {}
   (map
    (fn [[tag [desc & codec-args]]]
      [tag
       [desc
        (apply gloss/compile-frame codec-args)]])
    {1 [:checksum :uint16]
     2 [:unix-timestamp :uint64]
     3 [:mission-id :string]
     4 [:platform-tail-number :string]
     5 [:platform-heading :uint16 nil (scaler 0 65535 0 360)]
     6 [:platform-pitch :uint16 nil (scaler -32767 32767 -20 20)]
     7 [:platform-roll :uint16 nil (scaler -32767 32767 -50 50)]
     8 [:platform-true-airspeed :ubyte]
     9 [:platform-indicated-airspeed :ubyte]
     10 [:platform-designation (gloss/string :utf-8)]
     11 [:image-source-sensor (gloss/string :utf-8)]
     12 [:image-coordinate-system (gloss/string :utf-8)]
     13 [:sensor-lat :int32 nil lat-scaler]
     14 [:sensor-lon :int32 nil lon-scaler]
     15 [:sensor-true-alt :uint16 nil (scaler 0 65535 -900 19000)]
     16 [:sensor-horizontal-fov :uint16 nil (scaler 0 65535 0 180)]
     17 [:sensor-vertical-fov :uint16 nil (scaler 0 65535 0 180)]
     18 [:sensor-relative-azimuth :uint32 nil (scaler 0 4294967295 0 360)]
     19 [:sensor-relative-elevation :uint16 nil (scaler -2147483647 2147483647 -180 180)]
     20 [:sensor-relative-roll :uint32 nil (scaler 0 4294967295 0 360)]
     21 [:slant-range :uint32 nil (scaler 0 4294967295 0 5000000)]
     22 [:target-width :uint16 nil (scaler 0 65535 0 10000)]
     23 [:frame-center-lat :int32 nil lat-scaler]
     24 [:frame-center-lon :int32 nil lon-scaler]
     25 [:frame-center-elevation :uint16 nil (scaler 0 65535 -900 19000)]
     26 [:offset-corner-lat-point-1 :int16 nil pos-delta-scaler]
     27 [:offset-corner-lon-point-1 :int16 nil pos-delta-scaler]
     28 [:offset-corner-lat-point-2 :int16 nil pos-delta-scaler]
     29 [:offset-corner-lon-point-2 :int16 nil pos-delta-scaler]
     30 [:offset-corner-lat-point-3 :int16 nil pos-delta-scaler]
     31 [:offset-corner-lon-point-3 :int16 nil pos-delta-scaler]
     32 [:offset-corner-lat-point-4 :int16 nil pos-delta-scaler]
     33 [:offset-corner-lon-point-4 :int16 nil pos-delta-scaler]
     34 [:icing-detected :ubyte]
     35 [:wind-direction :uint16 nil (scaler 0 65535 0 360)]
     36 [:wind-speech :ubyte nil (scaler 0 255 0 100)]
     65 [:uas-ls-version-number :ubyte]
     })))


(defn read-ber [data offset]
  ;; FIXME: Handle > 127.
  [(get data offset)
   (inc offset)])


(defn bytes->hex [^bytes data]
  (string/join
   " "
   (map #(format "%x" %) (bytes->ints data))))


(defn parse-local-set
  ([data]
   (parse-local-set data 0))
  ([data offset]
   (loop [offset offset
          values '()]
     (if (>= offset (count data))
       (reverse values)
       (let [[tag-num offset] (read-ber data offset)
             [tag codec] (get local-set-tags tag-num)
             [len offset] (read-ber data offset)
             value (byte-array len)]
         (System/arraycopy data offset value 0 len)
         (recur
          (+ offset len)
          (conj
           values
           [(or tag tag-num)
            (if codec
              (gloss.io/decode codec value false)
              value)])))))))


(defn klvs-from-bytes [^bytes data]
  (KLV/bytesToList
   data
   0
   (count data)
   KLV$KeyLength/SixteenBytes
   KLV$LengthEncoding/BER))


(defn decode [^bytes data]
  (let [klvs (klvs-from-bytes data)]
    (map (fn [^KLV klv]
           (let [[tag desc _] (find-klv-signature (.getFullKey ^KLV klv))]
             (cond
               (nil? tag)
               (str "*Unknown* "
                    (bytes->hex (.getFullKey klv)) ":" (bytes->hex (.getValue klv)))
               (= tag :klv-basic-universal-metadata-set)
               [tag (decode (.getValue klv))]
               (= tag :klv-uas-datalink-local-dataset)
               [tag (parse-local-set (.getValue klv))]
               :else
               [tag (bytes->hex (.getValue klv))])))
         klvs)))


(defn -main [& args]
  (-> args
      first
      xio/binary-slurp
      decode
      pprint/pprint))
