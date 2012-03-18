#!/usr/bin/env python3
import httplib2
import json
import base64
import threading
import random
import time

test_arr = []

class TestThread(threading.Thread):
    def __init__(self, queriesPerThread, test):
        threading.Thread.__init__(self)
        self.queriesPerThread = queriesPerThread
        self.request = test.request
        self.uri = test.url
        self.test = test

    def run(self):
        http = httplib2.Http(disable_ssl_certificate_validation=True)

        headers = {'Content-Type':'application/json', "Accept":"application/json"}
        headers['authorization'] = 'Basic ' + base64.b64encode(("%s:%s" % ('root@tourenplaner.de', 'toureNPlaner')).encode('utf-8')).strip().decode('utf-8')

        self.response_arr = []

        for i in range (0, self.queriesPerThread):
            self.test.prepareTest(self)
            response, content = http.request(uri='https://gerbera:8081/' + self.uri, method='POST', body=json.dumps(self.request), headers=headers)
            self.response_arr.append(response)

class Test():
   def __init__(self, name):
      self.name = name
      self.url = ''
      self.request = ''

   # call initialize after user has selected test but before threads are created
   # purpose: for user input or anything else which should be done only once and not for every request
   def initialize(self):
      pass

   # should be called before every single request
   # purpose: for example for random requests
   @staticmethod
   def prepareTest(testThread):
      pass

def TestAnnotation(clazz):
   test_arr.append(clazz())


@TestAnnotation
class GetResponseTest(Test):
    def __init__(self):
       super().__init__('getresponse with user input (request id)')

    def initialize(self):
       self.url = 'getresponse?id=' + str(int(input("ID of Response: ")))


@TestAnnotation
class RandomAlgSPTest(Test):
    def __init__(self):
        super().__init__('random algsp with 2 points and without user input')
        self.url = 'algsp'

    @staticmethod
    def prepareTest(testThread):
       testThread.request = {'points':[{'lt': 487786110, 'ln' : 91794440}, {'lt': 535652780, 'ln': 100013890}]}
       points = []
       for i in range(0, 2):
          lat = random.randint(472600000, 548960000)
          lon = random.randint( 59000000, 149900000)
          points.append({'lt': lat, 'ln' : lon})
       testThread.request['points'] = points



@TestAnnotation
class ExtremeShortAlgSPTest(Test):
    def __init__(self):
        super().__init__('extreme short algsp without user input')
        self.url = 'algsp'
        self.request = {'points':[{'lt': 487131064, 'ln' : 92573199}, {'lt': 487129407, 'ln': 92572314}]}


@TestAnnotation
class GetUserWithInputTest(Test):
    def __init__(self):
       super().__init__('getuser with user input (user id)')

    def initialize(self):
       self.url = 'getuser?id=' + str(int(input("ID of User: ")))


@TestAnnotation
class GetUserWithoutInputTest(Test):
    def __init__(self):
        super().__init__('getuser without user input')
        self.url = 'getuser'

def main():

   i = 0
   for t in test_arr:
      print(str(i) + '\t' + t.name)
      i += 1

   chosen_test = int(input("\nChoose your test: "))

   test = test_arr[chosen_test]
   test.initialize()

   max = int(input("Max number of threads: "))
   queries = int(input("Number of queries per thread: "))

   thread_arr = []

   i = max

   while i > 0:
      thread = TestThread(queries, test)
      thread_arr.append(thread)
      i -= 1

   print('Start time measurement')
   start = time.time()

   for t in thread_arr:
      t.start()

   for t in thread_arr:
      t.join()

   end = time.time()
   print('End time measurement')

   failure = 'false'
   failure_cnt = 0

   for t in thread_arr:

      for response in t.response_arr:
        if not response['status'] == '200':
            if failure == 'false':
                #print(str(t.content, 'UTF-8') + '\n\nCould not compute all threads, error code: ' + response['status'])
                print('\n\nCould not compute all threads, error code: ' + response['status'])
            failure = 'true'
            failure_cnt += 1

   if failure == 'false':
      print('All threads computed (' + str(max) + '), all queries computed (' + str(max*queries) + ')')
   else:
      print('Failures: ' + str(failure_cnt) + ' / ' + str(max*queries))

   print('Time needed: ' + str(end - start) + ' s')


if __name__ == "__main__":
   main()